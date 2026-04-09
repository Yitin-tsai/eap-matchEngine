package com.eap.eap_matchengine.configuration.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
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
 * - order.created events (wallet-validated orders for matching)
 * - auction.bid.submitted events (auction bids for Redis collection)
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
     * MatchEngine queue for auction bid submitted events
     */
    @Bean
    public Queue matchEngineAuctionBidSubmittedQueue() {
        return QueueBuilder.durable(MATCH_ENGINE_AUCTION_BID_SUBMITTED_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .build();
    }

    /**
     * Bind matchEngine auction bid queue to auction.bid.submitted routing key
     */
    @Bean
    public Binding matchEngineAuctionBidSubmittedBinding(Queue matchEngineAuctionBidSubmittedQueue, TopicExchange auctionExchange) {
        return BindingBuilder.bind(matchEngineAuctionBidSubmittedQueue)
                .to(auctionExchange)
                .with(AUCTION_BID_SUBMITTED_KEY);
    }

    // ==================== Common ====================

    /**
     * 配置消息轉換器，使用 JSON 格式
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
