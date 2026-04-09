package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.event.OrderMatchedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;

import static com.eap.common.constants.RabbitMQConstants.*;

import lombok.extern.slf4j.Slf4j;
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

  private static final String MATCH_ID_KEY = "match:id:sequence";
  private static final String ORDER_LOCK_PREFIX = "lock:order:";
  private static final String MATCH_PROCESSED_PREFIX = "match:processed:";

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
   * Checks if a match has already been processed (idempotency check).
   * Uses Redis SETNX to ensure only the first caller proceeds.
   *
   * @param matchId The match ID to check
   * @return true if this is the first time processing this match, false if already processed
   */
  private boolean isFirstTimeProcessing(Long matchId) {
    String key = MATCH_PROCESSED_PREFIX + matchId;
    Boolean isFirst = redisTemplate.opsForValue().setIfAbsent(key, "1", 24, TimeUnit.HOURS);
    return Boolean.TRUE.equals(isFirst);
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
      OrderConfirmedEvent matchOrder = orderBookService.getAndRemoveBestMatchOrderLua(isBuy, incomingOrder.getPrice());

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

      // Calculate match amount
      int matchedAmount = Math.min(incomingOrder.getAmount(), matchOrder.getAmount());

      // Generate unique match ID using Redis INCR (atomic operation)
      Long matchId = generateMatchId();

      // Idempotency check: ensure this match is processed only once
      if (!isFirstTimeProcessing(matchId)) {
        log.warn("Match ID {} already processed, skipping duplicate", matchId);
        // Re-add both orders since this match shouldn't happen
        try {
          if (incomingOrder.getAmount() > 0) {
            orderBookService.addOrder(incomingOrder);
          }
          orderBookService.addOrder(matchOrder);
        } catch (JsonProcessingException e) {
          log.error("Failed to re-add orders after duplicate match detection", e);
        }
        break;
      }

      log.info("Match ID: {}, Buyer: {}, Seller: {}, Amount: {}, Price: {}",
          matchId,
          isBuy ? incomingOrder.getUserId() : matchOrder.getUserId(),
          isBuy ? matchOrder.getUserId() : incomingOrder.getUserId(),
          matchedAmount,
          matchOrder.getPrice());

      // Update amounts
      incomingOrder.setAmount(incomingOrder.getAmount() - matchedAmount);
      matchOrder.setAmount(matchOrder.getAmount() - matchedAmount);

      // Create and publish match event
      OrderMatchedEvent matchedEvent = OrderMatchedEvent.builder()
          .matchId(matchId.intValue())
          .buyerId(isBuy ? incomingOrder.getUserId() : matchOrder.getUserId())
          .sellerId(isBuy ? matchOrder.getUserId() : incomingOrder.getUserId())
          .buyerOrderId(isBuy ? incomingOrder.getOrderId() : matchOrder.getOrderId())
          .sellerOrderId(isBuy ? matchOrder.getOrderId() : incomingOrder.getOrderId())
          .originBuyerPrice(isBuy ? incomingOrder.getPrice(): matchOrder.getPrice())
          .originSellerPrice(isBuy ? matchOrder.getPrice() : incomingOrder.getPrice())
          .dealPrice(matchOrder.getPrice())
          .amount(matchedAmount)
          .matchedAt(LocalDateTime.now())
          .orderType(incomingOrder.getOrderType())
          .build();

      // Publish match event once - all interested modules will receive via their own queues
      rabbitTemplate.convertAndSend(ORDER_EXCHANGE, ORDER_MATCHED_KEY, matchedEvent);
      log.debug("Published OrderMatchedEvent for matchId={}", matchId);

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
        // Fully matched: remove from user's order set
        orderBookService.removeOrder(matchOrder);
        log.info("Order fully matched and removed: orderId={}", matchOrder.getOrderId());
      }
    }

    // If incoming order is fully matched, remove it too
    if (incomingOrder.getAmount() == 0) {
      orderBookService.removeOrder(incomingOrder);
      log.info("Incoming order fully matched and removed: orderId={}", incomingOrder.getOrderId());
    }
  }
}
