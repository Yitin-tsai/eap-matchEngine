package com.eap.eap_matchengine.configuration.reliability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.sql.SQLException;

import static com.eap.common.constants.RabbitMQConstants.MATCH_ENGINE_ORDER_ASSET_RESERVATION_SUCCEEDED_QUEUE;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchCdaDatabaseOutageMessageRecovererTest {

    @Mock
    private MatchCdaDatabaseOutageCircuitBreaker circuitBreaker;

    @Test
    void databaseOutageOnOwnedQueueOpensCircuitAndRequeues() {
        when(circuitBreaker.ownsQueue(MATCH_ENGINE_ORDER_ASSET_RESERVATION_SUCCEEDED_QUEUE)).thenReturn(true);
        SQLException failure = new SQLException("database unavailable", "08006");
        MatchCdaDatabaseOutageMessageRecoverer recoverer =
                new MatchCdaDatabaseOutageMessageRecoverer(circuitBreaker);

        assertThrows(ImmediateRequeueAmqpException.class,
                () -> recoverer.recover(message(MATCH_ENGINE_ORDER_ASSET_RESERVATION_SUCCEEDED_QUEUE), failure));

        verify(circuitBreaker).open(failure);
    }

    @Test
    void poisonFailureDoesNotOpenCircuitAndIsRejected() {
        when(circuitBreaker.ownsQueue(MATCH_ENGINE_ORDER_ASSET_RESERVATION_SUCCEEDED_QUEUE)).thenReturn(true);
        IllegalArgumentException failure = new IllegalArgumentException("invalid payload");
        MatchCdaDatabaseOutageMessageRecoverer recoverer =
                new MatchCdaDatabaseOutageMessageRecoverer(circuitBreaker);

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> recoverer.recover(message(MATCH_ENGINE_ORDER_ASSET_RESERVATION_SUCCEEDED_QUEUE), failure));

        verify(circuitBreaker, never()).open(failure);
    }

    private Message message(String queue) {
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue(queue);
        return new Message(new byte[0], properties);
    }
}
