package com.eap.eap_matchengine.configuration.config;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TradeCompletionBatchListenerConfig {

    @Bean
    public SimpleRabbitListenerContainerFactory tradeCompletionBatchListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Value("${eap.match-engine.listeners.completion-marker.batch-size:1}") int batchSize,
            @Value("${eap.match-engine.listeners.completion-marker.receive-timeout-ms:50}") long receiveTimeoutMs) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(Math.max(1, batchSize));
        factory.setReceiveTimeout(receiveTimeoutMs);
        return factory;
    }
}
