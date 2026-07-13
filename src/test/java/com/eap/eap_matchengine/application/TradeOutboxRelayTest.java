package com.eap.eap_matchengine.application;

import com.eap.common.constants.RabbitMQConstants;
import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_matchengine.configuration.observability.TradeOutboxMetrics;
import com.eap.eap_matchengine.configuration.repository.TradeOutboxRepository;
import com.eap.eap_matchengine.domain.entity.TradeOutboxEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeOutboxRelayTest {

    private final TradeOutboxRepository repository = mock(TradeOutboxRepository.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final TradeOutboxMetrics metrics = mock(TradeOutboxMetrics.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void marksConfirmedPublishesAsSent() throws Exception {
        TradeOutboxEntity entry = entry("TRADE-1");
        when(repository.findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(entry))
                .thenReturn(List.of());
        when(repository.markPendingAsSent(anyList(), any(LocalDateTime.class))).thenReturn(1);
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(
                eq(RabbitMQConstants.TRADE_EXCHANGE),
                eq(RabbitMQConstants.TRADE_EXECUTED_KEY),
                any(Message.class),
                any(CorrelationData.class));

        relay().pollAndPublish();

        verify(repository).markPendingAsSent(eq(List.of(entry.getId())), any(LocalDateTime.class));
        verify(metrics).published();
        verify(metrics).recordPublish(any(Duration.class));
    }

    @Test
    void schedulesRetryWhenBrokerNacks() throws Exception {
        TradeOutboxEntity entry = entry("TRADE-2");
        when(repository.findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "test nack"));
            return null;
        }).when(rabbitTemplate).send(
                anyString(),
                anyString(),
                any(Message.class),
                any(CorrelationData.class));

        relay().pollAndPublish();

        verify(repository).save(entry);
        verify(metrics).publishFailed();
        verify(metrics).retryScheduled();
    }

    @Test
    void usesDedicatedTemplateInvokeChannelForParallelPublishChunks() throws Exception {
        TradeOutboxEntity first = entry(1L, "TRADE-1");
        TradeOutboxEntity second = entry(2L, "TRADE-2");
        TradeOutboxEntity third = entry(3L, "TRADE-3");
        TradeOutboxEntity fourth = entry(4L, "TRADE-4");
        when(repository.findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(first, second, third, fourth))
                .thenReturn(List.of());
        when(repository.markPendingAsSent(anyList(), any(LocalDateTime.class))).thenReturn(4);

        doAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            RabbitOperations operations = mock(RabbitOperations.class);
            doAnswer(sendInvocation -> {
                CorrelationData correlationData = sendInvocation.getArgument(3);
                correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
                return null;
            }).when(operations).send(
                    eq(RabbitMQConstants.TRADE_EXCHANGE),
                    eq(RabbitMQConstants.TRADE_EXECUTED_KEY),
                    any(Message.class),
                    any(CorrelationData.class));
            callback.doInRabbit(operations);
            return null;
        }).when(rabbitTemplate).invoke(any(RabbitOperations.OperationsCallback.class));

        relay(2).pollAndPublish();

        verify(rabbitTemplate, times(2)).invoke(any(RabbitOperations.OperationsCallback.class));
        verify(repository).markPendingAsSent(eq(List.of(1L, 2L, 3L, 4L)), any(LocalDateTime.class));
        verify(metrics, times(4)).published();
    }

    private TradeOutboxRelay relay() {
        return relay(1);
    }

    private TradeOutboxRelay relay(int publishConcurrency) {
        return new TradeOutboxRelay(
                repository,
                rabbitTemplate,
                metrics,
                10,
                publishConcurrency,
                1000,
                3,
                100,
                1000);
    }

    private TradeOutboxEntity entry(String tradeId) throws Exception {
        return entry(1L, tradeId);
    }

    private TradeOutboxEntity entry(Long id, String tradeId) throws Exception {
        TradeExecutedEvent event = TradeExecutedEvent.builder()
                .tradeId(tradeId)
                .sequence(1L)
                .legacyMatchId(1)
                .marketId("ENERGY-SPOT")
                .buyerId(UUID.randomUUID())
                .sellerId(UUID.randomUUID())
                .buyerOrderId(UUID.randomUUID())
                .sellerOrderId(UUID.randomUUID())
                .originBuyerPrice(100)
                .originSellerPrice(90)
                .dealPrice(90)
                .quantity(1)
                .occurredAt(LocalDateTime.now())
                .build();
        TradeOutboxEntity entity = new TradeOutboxEntity(
                "TradeExecutedEvent",
                "TRADE",
                tradeId,
                RabbitMQConstants.TRADE_EXECUTED_KEY,
                objectMapper.writeValueAsString(event));
        entity.setId(id);
        return entity;
    }
}
