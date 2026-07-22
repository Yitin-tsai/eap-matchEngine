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
        stubInvokeSendConfirm(true, null);

        relay().pollAndPublish();

        verify(namedJdbcTemplate).update(contains("SET status = 'SENT'"), any(MapSqlParameterSource.class));
        verify(metrics).published();
        verify(metrics).recordPublish(any(Duration.class));
    }

    @Test
    void schedulesRetryWhenBrokerNacks() throws Exception {
        TestOutboxRow entry = entry("TRADE-2");
        stubPendingRows(List.of(entry));
        stubInvokeSendConfirm(false, "test nack");

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

    @Test
    void batchConfirmEnabled_waitsForChannelConfirmsBeforeMarkingSent() throws Exception {
        TestOutboxRow first = entry(1L, "TRADE-1");
        TestOutboxRow second = entry(2L, "TRADE-2");
        stubPendingRows(List.of(first, second), List.of());
        when(namedJdbcTemplate.update(contains("SET status = 'SENT'"), any(MapSqlParameterSource.class)))
                .thenReturn(2);

        doAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            RabbitOperations operations = mock(RabbitOperations.class);
            callback.doInRabbit(operations);
            verify(operations).waitForConfirmsOrDie(1000);
            return null;
        }).when(rabbitTemplate).invoke(any(RabbitOperations.OperationsCallback.class));

        relay(1, true).pollAndPublish();

        verify(namedJdbcTemplate).update(contains("SET status = 'SENT'"), any(MapSqlParameterSource.class));
        verify(metrics, times(2)).published();
    }

    @Test
    void rebuildsTradeExecutedPayloadWhenOutboxPayloadIsNull() throws Exception {
        TestOutboxRow entry = entryWithoutPayload(1L, "TRADE-REBUILD");
        stubPendingRows(List.of(entry), List.of());
        when(namedJdbcTemplate.update(contains("SET status = 'SENT'"), any(MapSqlParameterSource.class)))
                .thenReturn(1);

        doAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            RabbitOperations operations = mock(RabbitOperations.class);
            doAnswer(sendInvocation -> {
                Message message = sendInvocation.getArgument(2);
                TradeExecutedEvent published =
                        objectMapper.readValue(message.getBody(), TradeExecutedEvent.class);
                org.assertj.core.api.Assertions.assertThat(published.getTradeId()).isEqualTo("TRADE-REBUILD");
                org.assertj.core.api.Assertions.assertThat(published.getBuyerId()).isEqualTo(entry.event().getBuyerId());
                org.assertj.core.api.Assertions.assertThat(published.getQuantity()).isEqualTo(2);
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

        relay().pollAndPublish();

        verify(namedJdbcTemplate).update(contains("SET status = 'SENT'"), any(MapSqlParameterSource.class));
        verify(metrics).published();
    }

    private TradeOutboxRelay relay() {
        return relay(1);
    }

    private TradeOutboxRelay relay(int publishConcurrency) {
        return relay(publishConcurrency, false);
    }

    private TradeOutboxRelay relay(int publishConcurrency, boolean batchConfirmEnabled) {
        return new TradeOutboxRelay(
                jdbcTemplate,
                namedJdbcTemplate,
                rabbitTemplate,
                metrics,
                objectMapper,
                10,
                publishConcurrency,
                batchConfirmEnabled,
                1000,
                3,
                100,
                1000);
    }

    private void stubInvokeSendConfirm(boolean ack, String reason) {
        doAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            RabbitOperations operations = mock(RabbitOperations.class);
            doAnswer(sendInvocation -> {
                CorrelationData correlationData = sendInvocation.getArgument(3);
                correlationData.getFuture().complete(new CorrelationData.Confirm(ack, reason));
                return null;
            }).when(operations).send(
                    anyString(),
                    anyString(),
                    any(Message.class),
                    any(CorrelationData.class));
            callback.doInRabbit(operations);
            return null;
        }).when(rabbitTemplate).invoke(any(RabbitOperations.OperationsCallback.class));
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
        if (row.event() != null) {
            when(rs.getObject("sequence", Long.class)).thenReturn(row.event().getSequence());
            when(rs.getObject("legacy_match_id", Long.class)).thenReturn(row.event().getLegacyMatchId().longValue());
            when(rs.getString("market_id")).thenReturn(row.event().getMarketId());
            when(rs.getObject("buyer_id", UUID.class)).thenReturn(row.event().getBuyerId());
            when(rs.getObject("seller_id", UUID.class)).thenReturn(row.event().getSellerId());
            when(rs.getObject("buyer_order_id", UUID.class)).thenReturn(row.event().getBuyerOrderId());
            when(rs.getObject("seller_order_id", UUID.class)).thenReturn(row.event().getSellerOrderId());
            when(rs.getObject("buyer_market_sequence", Long.class)).thenReturn(row.event().getBuyerMarketSequence());
            when(rs.getObject("seller_market_sequence", Long.class)).thenReturn(row.event().getSellerMarketSequence());
            when(rs.getObject("origin_buyer_price", Integer.class)).thenReturn(row.event().getOriginBuyerPrice());
            when(rs.getObject("origin_seller_price", Integer.class)).thenReturn(row.event().getOriginSellerPrice());
            when(rs.getObject("deal_price", Integer.class)).thenReturn(row.event().getDealPrice());
            when(rs.getObject("quantity", Integer.class)).thenReturn(row.event().getQuantity());
            when(rs.getObject("occurred_at", LocalDateTime.class)).thenReturn(row.event().getOccurredAt());
        }
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
                null,
                0);
    }

    private TestOutboxRow entryWithoutPayload(Long id, String tradeId) {
        TradeExecutedEvent event = TradeExecutedEvent.builder()
                .tradeId(tradeId)
                .sequence(2L)
                .legacyMatchId(2)
                .marketId("ENERGY-SPOT")
                .buyerId(UUID.fromString("00000000-0000-0000-0000-000000000101"))
                .sellerId(UUID.fromString("00000000-0000-0000-0000-000000000102"))
                .buyerOrderId(UUID.fromString("00000000-0000-0000-0000-000000000103"))
                .sellerOrderId(UUID.fromString("00000000-0000-0000-0000-000000000104"))
                .buyerMarketSequence(11L)
                .sellerMarketSequence(12L)
                .originBuyerPrice(110)
                .originSellerPrice(100)
                .dealPrice(100)
                .quantity(2)
                .occurredAt(LocalDateTime.of(2026, 7, 22, 9, 30))
                .build();
        return new TestOutboxRow(
                id,
                "TradeExecutedEvent",
                tradeId,
                RabbitMQConstants.TRADE_EXECUTED_KEY,
                null,
                event,
                0);
    }

    private record TestOutboxRow(
            long id,
            String eventType,
            String aggregateId,
            String routingKey,
            String payload,
            TradeExecutedEvent event,
            int attemptCount) {
    }
}
