package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.event.TradeExecutedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * Service responsible for matching buy and sell orders in the trading system.
 * Implements the order matching logic with ACID compliance:
 * - Atomicity: Uses Lua scripts for atomic Redis operations
 * - Consistency: Uses distributed locks to prevent race conditions
 * - Isolation: Ensures no concurrent modifications to same order
 * - Durability: Redis persistence ensures data recovery
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingEngineService {

  private final RedisOrderBookService orderBookService;
  private final RedissonClient redissonClient;
  private final TradeExecutionRecorder tradeExecutionRecorder;
  private final MatchingEngineMetrics metrics;

  private static final String ORDER_LOCK_PREFIX = "lock:order:";

  /**
   * Attempts to match an incoming order with existing orders in the order book.
   * The matching process follows these steps with ACID guarantees:
   * 1. Checks for matching orders in the opposite order book
   * 2. If no matches found, adds the order to the appropriate order book atomically
   * 3. If matches found, processes them in order with distributed locks:
   *    - Matches the maximum possible quantity
   *    - Updates the quantities of both orders
   *    - Creates and publishes a matched event with idempotency check
   *    - Removes fully matched orders atomically
   *    - Adds remaining quantity back to order book atomically with lock protection
   *
   * @param incomingOrder The new order to be matched
   */
  public void tryMatch(OrderConfirmedEvent incomingOrder) {
    tryMatch(incomingOrder, null);
  }

  GuardedMatchResult tryMatchGuarded(
      OrderConfirmedEvent incomingOrder,
      IncomingOrderProcessingStore.Claim processingClaim) {
    return tryMatch(incomingOrder, processingClaim);
  }

  private GuardedMatchResult tryMatch(
      OrderConfirmedEvent incomingOrder,
      IncomingOrderProcessingStore.Claim processingClaim) {
    Instant tryMatchStartedAt = Instant.now();
    boolean addedToBook = false;
    int recordedTrades = 0;
    try {
      boolean isBuy = incomingOrder.getOrderType().equalsIgnoreCase("BUY");

      while (incomingOrder.getAmount() > 0) {
        // Reserve the resting order before writing the durable trade fact. If no match exists,
        // Redis adds the incoming order in the same Lua call to avoid a second no-match round trip.
        RedisOrderBookService.MatchOrAddResult matchAttempt =
            reserveBestMatchOrAddOrder(incomingOrder, processingClaim);

        if (matchAttempt.incomingOrderAdmission()
            == RedisOrderBookService.IncomingOrderAdmission.DUPLICATE) {
          return GuardedMatchResult.DUPLICATE;
        }
        if (matchAttempt.incomingOrderAdmission()
            == RedisOrderBookService.IncomingOrderAdmission.IN_PROGRESS) {
          return GuardedMatchResult.IN_PROGRESS;
        }

        if (matchAttempt.orderAdded()) {
          addedToBook = true;
          metrics.orderAdded();
          log.info("No matching order found, added to order book: orderId={}, amount={}",
              incomingOrder.getOrderId(), incomingOrder.getAmount());
          break;
        }

        RedisOrderBookService.ReservedMatch reservedMatch = matchAttempt.reservedMatch();
        OrderConfirmedEvent matchOrder = reservedMatch.order();
        int incomingAmountBeforeMatch = incomingOrder.getAmount();
        int matchOrderAmountBeforeMatch = matchOrder.getAmount();

        // Calculate match amount
        int matchedAmount = Math.min(incomingOrder.getAmount(), matchOrder.getAmount());

        TradeExecutedEvent tradeExecutedEvent;
        boolean reservationCleanupDeferred = false;
        try {
          Long matchId = reservedMatch.matchId();

          log.info("Match ID: {}, Buyer: {}, Seller: {}, Amount: {}, Price: {}",
              matchId,
              isBuy ? incomingOrder.getUserId() : matchOrder.getUserId(),
              isBuy ? matchOrder.getUserId() : incomingOrder.getUserId(),
              matchedAmount,
              matchOrder.getPrice());

          tradeExecutedEvent = toTradeExecutedEvent(incomingOrder, matchOrder, isBuy, matchId, matchedAmount);
          ReservationCleanupTask cleanupTask = matchOrderAmountBeforeMatch == matchedAmount
              ? ReservationCleanupTask.completed(
                  tradeExecutedEvent.getTradeId(),
                  matchOrder.getOrderId(),
                  matchOrder.getUserId())
              : null;
          reservationCleanupDeferred = recordTrade(tradeExecutedEvent, cleanupTask);
          recordedTrades++;
        } catch (RuntimeException e) {
          releaseReservedRestingOrder(matchOrder, matchOrderAmountBeforeMatch, e);
          throw e;
        }
        log.debug("Persisted TradeExecutedEvent for tradeId={}", tradeExecutedEvent.getTradeId());

        // Update amounts only after the durable trade fact is committed. If persistence fails,
        // the popped resting order is restored with its original amount and the incoming order can retry.
        incomingOrder.setAmount(incomingAmountBeforeMatch - matchedAmount);
        matchOrder.setAmount(matchOrderAmountBeforeMatch - matchedAmount);

        // Handle partial match with distributed lock to prevent race conditions
        if (matchOrder.getAmount() > 0) {
          // Partial match: release remaining amount back to the visible orderbook with lock protection
          String lockKey = ORDER_LOCK_PREFIX + matchOrder.getOrderId();
          RLock lock = redissonClient.getLock(lockKey);

          try {
            // Try to acquire lock with timeout (wait up to 5s, auto-release after 10s)
            boolean locked = lock.tryLock(5, 10, TimeUnit.SECONDS);

            if (locked) {
              try {
                releaseReservedOrder(matchOrder);
                log.info("Partial match: released remaining reserved order atomically: orderId={}, remainingAmount={}",
                    matchOrder.getOrderId(), matchOrder.getAmount());
              } catch (JsonProcessingException e) {
                log.error("Failed to release partial reserved order: orderId={}", matchOrder.getOrderId(), e);
                throw new RuntimeException("Failed to release partial reserved order", e);
              } finally {
                lock.unlock();
              }
            } else {
              log.error("Failed to acquire lock for order: orderId={}", matchOrder.getOrderId());
              throw new RuntimeException("Failed to acquire lock for partial order re-add");
            }
          } catch (InterruptedException e) {
            log.error("Interrupted while waiting for lock: orderId={}", matchOrder.getOrderId(), e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for lock", e);
          }
        } else {
          if (reservationCleanupDeferred) {
            log.debug("Reserved order cleanup deferred: orderId={}, tradeId={}",
                matchOrder.getOrderId(), tradeExecutedEvent.getTradeId());
          } else {
            completeReservedOrder(matchOrder);
            log.info("Order fully matched and completed from reservation: orderId={}", matchOrder.getOrderId());
          }
        }
      }
      return GuardedMatchResult.PROCESSED;
    } finally {
      Duration duration = Duration.between(tryMatchStartedAt, Instant.now());
      metrics.recordTryMatch(duration);
      metrics.recordTryMatchOutcome(tryMatchOutcome(recordedTrades, addedToBook, incomingOrder.getAmount()), duration);
    }
  }

  private String tryMatchOutcome(int recordedTrades, boolean addedToBook, int remainingAmount) {
    if (recordedTrades == 0 && addedToBook) {
      return "added_to_book";
    }
    if (recordedTrades > 0 && remainingAmount <= 0) {
      return "fully_matched";
    }
    if (recordedTrades > 0) {
      return "matched_with_remainder";
    }
    return "no_op";
  }

  private RedisOrderBookService.MatchOrAddResult reserveBestMatchOrAddOrder(
      OrderConfirmedEvent incomingOrder,
      IncomingOrderProcessingStore.Claim processingClaim) {
    Instant startedAt = Instant.now();
    try {
      return processingClaim == null
          ? orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incomingOrder)
          : orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incomingOrder, processingClaim);
    } finally {
      metrics.recordReserve(Duration.between(startedAt, Instant.now()));
    }
  }

  enum GuardedMatchResult {
    PROCESSED,
    DUPLICATE,
    IN_PROGRESS
  }

  private boolean recordTrade(TradeExecutedEvent tradeExecutedEvent, ReservationCleanupTask cleanupTask) {
    Instant startedAt = Instant.now();
    try {
      boolean cleanupDeferred = tradeExecutionRecorder.record(tradeExecutedEvent, cleanupTask);
      metrics.tradeRecorded();
      return cleanupDeferred;
    } finally {
      metrics.recordTradeRecord(Duration.between(startedAt, Instant.now()));
    }
  }

  private void completeReservedOrder(OrderConfirmedEvent matchOrder) {
    Instant startedAt = Instant.now();
    try {
      orderBookService.completeReservedOrder(matchOrder);
      metrics.reservationCompleted();
    } finally {
      metrics.recordCompleteReservation(Duration.between(startedAt, Instant.now()));
    }
  }

  private void releaseReservedOrder(OrderConfirmedEvent matchOrder) throws JsonProcessingException {
    Instant startedAt = Instant.now();
    try {
      orderBookService.releaseReservedOrder(matchOrder);
      metrics.reservationReleased();
    } finally {
      metrics.recordReleaseReservation(Duration.between(startedAt, Instant.now()));
    }
  }

  private void releaseReservedRestingOrder(
      OrderConfirmedEvent matchOrder,
      int originalAmount,
      RuntimeException cause) {
    matchOrder.setAmount(originalAmount);
    try {
      releaseReservedOrder(matchOrder);
      log.warn("Released reserved resting order after trade persistence failure: orderId={}, amount={}",
          matchOrder.getOrderId(), originalAmount, cause);
    } catch (JsonProcessingException compensationFailure) {
      cause.addSuppressed(compensationFailure);
      log.error("Failed to release reserved resting order after trade persistence failure: orderId={}",
          matchOrder.getOrderId(), compensationFailure);
    }
  }

  private TradeExecutedEvent toTradeExecutedEvent(
      OrderConfirmedEvent incomingOrder,
      OrderConfirmedEvent matchOrder,
      boolean incomingIsBuy,
      Long matchId,
      int matchedAmount) {
    Long sequence = matchId;
    String marketId = incomingOrder.getMarketId() == null ? "UNKNOWN" : incomingOrder.getMarketId();
    return TradeExecutedEvent.builder()
        .tradeId(marketId + "-" + sequence)
        .sequence(sequence)
        .legacyMatchId(matchId.intValue())
        .marketId(marketId)
        .buyerId(incomingIsBuy ? incomingOrder.getUserId() : matchOrder.getUserId())
        .sellerId(incomingIsBuy ? matchOrder.getUserId() : incomingOrder.getUserId())
        .buyerOrderId(incomingIsBuy ? incomingOrder.getOrderId() : matchOrder.getOrderId())
        .sellerOrderId(incomingIsBuy ? matchOrder.getOrderId() : incomingOrder.getOrderId())
        .buyerMarketSequence(incomingIsBuy ? incomingOrder.getMarketSequence() : matchOrder.getMarketSequence())
        .sellerMarketSequence(incomingIsBuy ? matchOrder.getMarketSequence() : incomingOrder.getMarketSequence())
        .originBuyerPrice(incomingIsBuy ? incomingOrder.getPrice() : matchOrder.getPrice())
        .originSellerPrice(incomingIsBuy ? matchOrder.getPrice() : incomingOrder.getPrice())
        .dealPrice(matchOrder.getPrice())
        .quantity(matchedAmount)
        .occurredAt(LocalDateTime.now())
        .build();
  }
}
