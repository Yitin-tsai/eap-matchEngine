package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderAssetReservationSucceededEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderAssetReservationSucceededListenerTest {

    @Mock MatchOrderAdmissionInbox inbox;
    @Mock MatchOrderAdmissionInboxMetrics inboxMetrics;
    @Mock MatchingEngineMetrics metrics;

    @Test
    void receiveSuccess_shouldPersistBeforeListenerReturns() {
        OrderAssetReservationSucceededEvent event = event();
        when(inbox.receive(event)).thenReturn(MatchOrderAdmissionInbox.ReceiveOutcome.ACCEPTED);
        OrderAssetReservationSucceededListener listener =
                new OrderAssetReservationSucceededListener(inbox, inboxMetrics, metrics);

        listener.onReservationSucceeded(event);

        verify(inbox).receive(event);
        verify(inboxMetrics).received(MatchOrderAdmissionInbox.ReceiveOutcome.ACCEPTED);
        verify(metrics).recordOrderAssetReservationSucceededListener(any(Duration.class));
    }

    @Test
    void databaseFailure_shouldEscapeSoRabbitDoesNotAcknowledgeTheMessage() {
        OrderAssetReservationSucceededEvent event = event();
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("PostgreSQL unavailable");
        when(inbox.receive(event)).thenThrow(failure);
        OrderAssetReservationSucceededListener listener =
                new OrderAssetReservationSucceededListener(inbox, inboxMetrics, metrics);

        assertThatThrownBy(() -> listener.onReservationSucceeded(event)).isSameAs(failure);

        verify(metrics).recordOrderAssetReservationSucceededListener(any(Duration.class));
    }

    private OrderAssetReservationSucceededEvent event() {
        return OrderAssetReservationSucceededEvent.builder()
                .orderId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .marketId("TEST-MARKET")
                .marketSequence(1L)
                .price(100)
                .amount(1)
                .orderType("BUY")
                .build();
    }
}
