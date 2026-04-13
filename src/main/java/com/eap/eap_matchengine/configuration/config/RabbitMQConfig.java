package com.eap.eap_matchengine.configuration.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.eap.common.constants.RabbitMQConstants.*;

/**
 * MatchEngine Module RabbitMQ Configuration
 *
 * This module consumes:
 * - order.confirmed events (wallet-validated orders for CDA matching)
 * - auction.bid.confirmed events (wallet-confirmed auction bids for Redis collection)
 *
 * This module publishes:
 * - order.matched events (match results)
 * - auction.created events (new auction opened)
 * - auction.cleared events (auction clearing results)
 *
 * Topology: Each module gets its own queues bound to shared routing keys
 */
@Configuration
public class RabbitMQConfig {

    // ==================== CDA (Continuous Double Auction) ====================

    /**
     * MatchEngine-specific queue for order confirmed events (wallet validation complete)
     */
    @Bean
    public Queue matchEngineOrderConfirmedQueue() {
        return QueueBuilder.durable(MATCH_ENGINE_ORDER_CONFIRMED_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .build();
    }

    /**
     * 訂單交換機
     */
    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(ORDER_EXCHANGE);
    }

    /**
     * Bind matchEngine queue to order.confirmed routing key
     */
    @Bean
    public Binding matchEngineOrderConfirmedBinding(Queue matchEngineOrderConfirmedQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(matchEngineOrderConfirmedQueue)
                .to(orderExchange)
                .with(ORDER_CONFIRMED_KEY);
    }

    // ==================== Auction (Timed Double Auction) ====================

    /**
     * Auction exchange for auction-related events
     */
    @Bean
    public TopicExchange auctionExchange() {
        return new TopicExchange(AUCTION_EXCHANGE);
    }

    /**
     * MatchEngine queue for wallet-confirmed auction bid events.
     * Only bids that have passed wallet fund-locking reach this queue.
     */
    @Bean
    public Queue matchEngineAuctionBidConfirmedQueue() {
        return QueueBuilder.durable(MATCH_ENGINE_AUCTION_BID_CONFIRMED_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .build();
    }

    /**
     * Bind matchEngine auction bid queue to auction.bid.confirmed routing key
     */
    @Bean
    public Binding matchEngineAuctionBidConfirmedBinding(Queue matchEngineAuctionBidConfirmedQueue, TopicExchange auctionExchange) {
        return BindingBuilder.bind(matchEngineAuctionBidConfirmedQueue)
                .to(auctionExchange)
                .with(AUCTION_BID_CONFIRMED_KEY);
    }

    // ==================== Common ====================

    // --- Dead Letter Exchange / Queue (shared, idempotent declare) ---

    @Bean
    public FanoutExchange deadLetterExchange() {
        return new FanoutExchange(DEAD_LETTER_EXCHANGE);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding dlqBinding(Queue deadLetterQueue, FanoutExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
    }

    /**
     * 配置消息轉換器，使用 JSON 格式
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
