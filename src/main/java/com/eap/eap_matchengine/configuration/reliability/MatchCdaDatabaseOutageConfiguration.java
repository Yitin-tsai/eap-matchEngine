package com.eap.eap_matchengine.configuration.reliability;

import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MatchCdaDatabaseOutageConfiguration {

    @Bean
    public MessageRecoverer matchCdaDatabaseOutageMessageRecoverer(
            MatchCdaDatabaseOutageCircuitBreaker circuitBreaker) {
        return new MatchCdaDatabaseOutageMessageRecoverer(circuitBreaker);
    }

    @Bean
    public ContainerCustomizer<SimpleMessageListenerContainer> matchForceStopContainerCustomizer() {
        return container -> container.setForceStop(true);
    }
}
