package com.eap.eap_matchengine.application;

import com.eap.common.constants.RabbitMQConstants;
import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_matchengine.configuration.observability.TradeOutboxMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeOutboxRelayTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final NamedParameterJdbcTemplate namedJdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final TradeOutboxMetrics metrics = mock(TradeOutboxMetrics.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void marksConfirmedPublishesAsSent() throws Exception {
        TestOutboxRow entry = entry("TRADE-1");
        stubPendingRows(List.of(entry), List.of());
        when(namedJdbcTemplate.update(contains("SET status = 'SENT'"), any(MapSqlParameterSource.class)))
                .thenReturn(1);
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

        verify(namedJdbcTemplate).update(contains("SET status = 'SENT'"), any(MapSqlParameterSource.class));
        verify(metrics).published();
        verify(metrics).recordPublish(any(Duration.class));
    }

    @Test
    void schedulesRetryWhenBrokerNacks() throws Exception {
        TestOutboxRow entry = entry("TRADE-2");
        stubPendingRows(List.of(entry));
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

        verify(namedJdbcTemplate).update(contains("SET attempt_count = :attemptCount"), any(MapSqlParameterSource.class));
        verify(metrics).publishFailed();
        verify(metrics).retryScheduled();
    }

    @Test
    void usesDedicatedTemplateInvokeChannelForParallelPublishChunks() throws Exception {
        TestOutboxRow first = entry(1L, "TRADE-1");
        TestOutboxRow second = entry(2L, "TRADE-2");
        TestOutboxRow third = entry(3L, "TRADE-3");
        TestOutboxRow fourth = entry(4L, "TRADE-4");
        stubPendingRows(List.of(first, second, third, fourth), List.of());
        when(namedJdbcTemplate.update(contains("SET status = 'SENT'"), any(MapSqlParameterSource.class)))
                .thenReturn(4);

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
        verify(namedJdbcTemplate).update(contains("SET status = 'SENT'"), any(MapSqlParameterSource.class));
        verify(metrics, times(4)).published();
    }

    private TradeOutboxRelay relay() {
        return relay(1);
    }

    private TradeOutboxRelay relay(int publishConcurrency) {
        return new TradeOutboxRelay(
                jdbcTemplate,
                namedJdbcTemplate,
                rabbitTemplate,
                metrics,
                10,
                publishConcurrency,
                1000,
                3,
                100,
                1000);
    }

    private void stubPendingRows(List<TestOutboxRow> firstBatch) {
        stubPendingRows(firstBatch, List.of());
    }

    @SafeVarargs
    private void stubPendingRows(List<TestOutboxRow>... batches) {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            int invocationIndex = stubInvocationCount++;
            List<TestOutboxRow> batch = invocationIndex < batches.length ? batches[invocationIndex] : List.of();
            java.util.ArrayList<Object> rows = new java.util.ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                rows.add(mapper.mapRow(resultSet(batch.get(i)), i));
            }
            return rows;
        }).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any());
    }

    private int stubInvocationCount;

    private ResultSet resultSet(TestOutboxRow row) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(row.id());
        when(rs.getString("event_type")).thenReturn(row.eventType());
        when(rs.getString("aggregate_id")).thenReturn(row.aggregateId());
        when(rs.getString("routing_key")).thenReturn(row.routingKey());
        when(rs.getString("payload")).thenReturn(row.payload());
        when(rs.getInt("attempt_count")).thenReturn(row.attemptCount());
        return rs;
    }

    private TestOutboxRow entry(String tradeId) throws Exception {
        return entry(1L, tradeId);
    }

    private TestOutboxRow entry(Long id, String tradeId) throws Exception {
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
        return new TestOutboxRow(
                id,
                "TradeExecutedEvent",
                tradeId,
                RabbitMQConstants.TRADE_EXECUTED_KEY,
                objectMapper.writeValueAsString(event),
                0);
    }

    private record TestOutboxRow(
            long id,
            String eventType,
            String aggregateId,
            String routingKey,
            String payload,
            int attemptCount) {
    }
}
