package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.event.OrderMatchedEvent;
import com.eap.common.event.TradeExecutedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;

import static com.eap.common.constants.RabbitMQConstants.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
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
  private final RabbitTemplate rabbitTemplate;
  private final RedisTemplate<String, String> redisTemplate;
  private final RedissonClient redissonClient;
  private final TradeExecutionRecorder tradeExecutionRecorder;

  @Value("${eap.match-engine.legacy-order-matched-publish.enabled:true}")
  private boolean legacyOrderMatchedPublishEnabled;

  private static final String MATCH_ID_KEY = "match:id:sequence";
  private static final String ORDER_LOCK_PREFIX = "lock:order:";

  /**
   * Generates a unique match ID using Redis INCR operation.
   * This ensures thread-safe, distributed unique ID generation.
   *
   * @return A unique match ID as Long
   */
  private Long generateMatchId() {
    return redisTemplate.opsForValue().increment(MATCH_ID_KEY);
  }

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
    boolean isBuy = incomingOrder.getOrderType().equalsIgnoreCase("BUY");

    while (incomingOrder.getAmount() > 0) {
      // Use Lua script to atomically get and remove best match order
      OrderConfirmedEvent matchOrder = orderBookService.getAndRemoveBestMatchOrderLua(incomingOrder);

      if (matchOrder == null) {
        // No matching order found, add remaining order to orderbook atomically
        try {
          orderBookService.addOrder(incomingOrder);
          log.info("No matching order found, added to order book: orderId={}, amount={}",
              incomingOrder.getOrderId(), incomingOrder.getAmount());
        } catch (JsonProcessingException e) {
          log.error("Failed to add order to orderbook", e);
          throw new RuntimeException("Failed to add order to orderbook", e);
        }
        break;
      }

      int incomingAmountBeforeMatch = incomingOrder.getAmount();
      int matchOrderAmountBeforeMatch = matchOrder.getAmount();

      // Calculate match amount
      int matchedAmount = Math.min(incomingOrder.getAmount(), matchOrder.getAmount());

      OrderMatchedEvent matchedEvent;
      TradeExecutedEvent tradeExecutedEvent;
      try {
        // Generate unique match ID using Redis INCR (atomic operation)
        Long matchId = generateMatchId();

        log.info("Match ID: {}, Buyer: {}, Seller: {}, Amount: {}, Price: {}",
            matchId,
            isBuy ? incomingOrder.getUserId() : matchOrder.getUserId(),
            isBuy ? matchOrder.getUserId() : incomingOrder.getUserId(),
            matchedAmount,
            matchOrder.getPrice());

        // Create and publish match event
        matchedEvent = OrderMatchedEvent.builder()
            .matchId(matchId.intValue())
            .buyerId(isBuy ? incomingOrder.getUserId() : matchOrder.getUserId())
            .sellerId(isBuy ? matchOrder.getUserId() : incomingOrder.getUserId())
            .buyerOrderId(isBuy ? incomingOrder.getOrderId() : matchOrder.getOrderId())
            .sellerOrderId(isBuy ? matchOrder.getOrderId() : incomingOrder.getOrderId())
            .marketId(incomingOrder.getMarketId())
            .buyerMarketSequence(isBuy ? incomingOrder.getMarketSequence() : matchOrder.getMarketSequence())
            .sellerMarketSequence(isBuy ? matchOrder.getMarketSequence() : incomingOrder.getMarketSequence())
            .originBuyerPrice(isBuy ? incomingOrder.getPrice(): matchOrder.getPrice())
            .originSellerPrice(isBuy ? matchOrder.getPrice() : incomingOrder.getPrice())
            .dealPrice(matchOrder.getPrice())
            .amount(matchedAmount)
            .matchedAt(LocalDateTime.now())
            .orderType(incomingOrder.getOrderType())
            .build();

        tradeExecutedEvent = toTradeExecutedEvent(matchedEvent);
        tradeExecutionRecorder.record(tradeExecutedEvent);
      } catch (RuntimeException e) {
        reAddPoppedRestingOrder(matchOrder, matchOrderAmountBeforeMatch, e);
        throw e;
      }
      log.debug("Persisted TradeExecutedEvent for tradeId={}", tradeExecutedEvent.getTradeId());

      // Update amounts only after the durable trade fact is committed. If persistence fails,
      // the popped resting order is restored with its original amount and the incoming order can retry.
      incomingOrder.setAmount(incomingAmountBeforeMatch - matchedAmount);
      matchOrder.setAmount(matchOrderAmountBeforeMatch - matchedAmount);

      if (legacyOrderMatchedPublishEnabled) {
        // Legacy event path kept for backward compatibility during migration.
        rabbitTemplate.convertAndSend(ORDER_EXCHANGE, ORDER_MATCHED_KEY, matchedEvent);
        log.debug("Published legacy OrderMatchedEvent for matchId={}", matchedEvent.getMatchId());
      }

      // Handle partial match with distributed lock to prevent race conditions
      if (matchOrder.getAmount() > 0) {
        // Partial match: re-add remaining amount with lock protection
        String lockKey = ORDER_LOCK_PREFIX + matchOrder.getOrderId();
        RLock lock = redissonClient.getLock(lockKey);

        try {
          // Try to acquire lock with timeout (wait up to 5s, auto-release after 10s)
          boolean locked = lock.tryLock(5, 10, TimeUnit.SECONDS);

          if (locked) {
            try {
              // Re-add the partial order atomically
              orderBookService.addOrder(matchOrder);
              log.info("Partial match: re-added remaining order atomically: orderId={}, remainingAmount={}",
                  matchOrder.getOrderId(), matchOrder.getAmount());
            } catch (JsonProcessingException e) {
              log.error("Failed to re-add partial order: orderId={}", matchOrder.getOrderId(), e);
              throw new RuntimeException("Failed to re-add partial order", e);
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
        // Fully matched: getAndRemoveBestMatchOrderLua already removed the orderbook entry and order detail.
        // Only unlink the order from the user's open-order set here to avoid a redundant Redis Lua round trip.
        orderBookService.unlinkUserOrder(matchOrder);
        log.info("Order fully matched and unlinked from user open orders: orderId={}", matchOrder.getOrderId());
      }
    }
  }

  private void reAddPoppedRestingOrder(
      OrderConfirmedEvent matchOrder,
      int originalAmount,
      RuntimeException cause) {
    matchOrder.setAmount(originalAmount);
    try {
      orderBookService.addOrder(matchOrder);
      log.warn("Re-added popped resting order after trade persistence failure: orderId={}, amount={}",
          matchOrder.getOrderId(), originalAmount, cause);
    } catch (JsonProcessingException compensationFailure) {
      cause.addSuppressed(compensationFailure);
      log.error("Failed to re-add popped resting order after trade persistence failure: orderId={}",
          matchOrder.getOrderId(), compensationFailure);
    }
  }

  private TradeExecutedEvent toTradeExecutedEvent(OrderMatchedEvent matchedEvent) {
    Long sequence = matchedEvent.getMatchId().longValue();
    String marketId = matchedEvent.getMarketId() == null ? "UNKNOWN" : matchedEvent.getMarketId();
    return TradeExecutedEvent.builder()
        .tradeId(marketId + "-" + sequence)
        .sequence(sequence)
        .legacyMatchId(matchedEvent.getMatchId())
        .marketId(marketId)
        .buyerId(matchedEvent.getBuyerId())
        .sellerId(matchedEvent.getSellerId())
        .buyerOrderId(matchedEvent.getBuyerOrderId())
        .sellerOrderId(matchedEvent.getSellerOrderId())
        .buyerMarketSequence(matchedEvent.getBuyerMarketSequence())
        .sellerMarketSequence(matchedEvent.getSellerMarketSequence())
        .originBuyerPrice(matchedEvent.getOriginBuyerPrice())
        .originSellerPrice(matchedEvent.getOriginSellerPrice())
        .dealPrice(matchedEvent.getDealPrice())
        .quantity(matchedEvent.getAmount())
        .occurredAt(matchedEvent.getMatchedAt())
        .build();
  }
}
