package com.eap.eap_matchengine.loadtest;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class OutboxRelayCeilingProbe {

    private static final String DEFAULT_RABBIT_HOST = "localhost";
    private static final int DEFAULT_RABBIT_PORT = 5672;
    private static final String DEFAULT_RABBIT_USERNAME = "admin";
    private static final String DEFAULT_RABBIT_PASSWORD = "admin123";
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin123";
    private static final String DEFAULT_PROBE_EXCHANGE = "eap.relay.probe.exchange";
    private static final String DEFAULT_PROBE_ROUTING_KEY = "relay.probe";

    public static void main(String[] args) throws Exception {
        Config config = Config.from(args);
        ServiceSpec spec = ServiceSpec.forName(config.service(), config.jdbcUrl());
        if (config.seedRows()) {
            seedRows(config, spec);
        }
        Result result = run(config, spec);
        printJson(config, result);
        if (result.failures() > 0) {
            throw new IllegalStateException("Outbox relay ceiling probe failed rows=" + result.failures());
        }
    }

    private static void seedRows(Config config, ServiceSpec spec) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                spec.jdbcUrl(),
                config.username(),
                config.password());
             PreparedStatement statement = connection.prepareStatement(spec.seedSql())) {
            connection.setAutoCommit(false);
            int batched = 0;
            for (int index = 1; index <= config.events(); index++) {
                spec.bindSeed(statement, config.marketId(), index);
                statement.addBatch();
                batched++;
                if (batched >= 1000) {
                    statement.executeBatch();
                    connection.commit();
                    batched = 0;
                }
            }
            if (batched > 0) {
                statement.executeBatch();
                connection.commit();
            }
        }
    }

    private static Result run(Config config, ServiceSpec spec) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.rabbitHost());
        factory.setPort(config.rabbitPort());
        factory.setUsername(config.rabbitUsername());
        factory.setPassword(config.rabbitPassword());

        try (Connection db = DriverManager.getConnection(spec.jdbcUrl(), config.username(), config.password());
             com.rabbitmq.client.Connection rabbit = factory.newConnection("eap-outbox-relay-ceiling-probe");
             Channel channel = rabbit.createChannel()) {
            db.setAutoCommit(false);
            channel.confirmSelect();
            channel.exchangeDeclare(config.probeExchange(), "topic", true, false, null);
            String queue = "eap.relay.probe." + config.marketId();
            channel.queueDeclare(queue, true, false, false, null);
            channel.queueBind(queue, config.probeExchange(), config.probeRoutingKey());
            channel.queuePurge(queue);

            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .contentEncoding(StandardCharsets.UTF_8.name())
                    .deliveryMode(2)
                    .build();

            ProbeTimers timers = new ProbeTimers();
            int completed = 0;
            int failures = 0;
            int batches = 0;
            long started = System.nanoTime();
            try {
                while (completed + failures < config.events()) {
                    long batchStarted = System.nanoTime();
                    List<RelayRow> rows;
                    long selectStarted = System.nanoTime();
                    try (PreparedStatement select = db.prepareStatement(spec.selectSql())) {
                        select.setString(1, spec.selectPattern(config.marketId()));
                        select.setInt(2, config.batchSize());
                        rows = selectRows(select);
                    } finally {
                        timers.selectNanos += System.nanoTime() - selectStarted;
                    }
                    if (rows.isEmpty()) {
                        break;
                    }

                    try {
                        long enqueueStarted = System.nanoTime();
                        for (RelayRow row : rows) {
                            channel.basicPublish(
                                    config.probeExchange(),
                                    config.probeRoutingKey(),
                                    false,
                                    properties,
                                    row.payload().getBytes(StandardCharsets.UTF_8));
                        }
                        timers.enqueueNanos += System.nanoTime() - enqueueStarted;

                        long confirmStarted = System.nanoTime();
                        channel.waitForConfirmsOrDie(config.confirmTimeoutMs());
                        timers.confirmNanos += System.nanoTime() - confirmStarted;

                        long markStarted = System.nanoTime();
                        try (PreparedStatement markSent = db.prepareStatement(spec.markSentSql(rows.size()))) {
                            spec.bindMarkSent(markSent, rows);
                            int marked = markSent.executeUpdate();
                            if (marked != rows.size()) {
                                throw new IllegalStateException("Expected to mark " + rows.size()
                                        + " rows SENT, but updated " + marked);
                            }
                        } finally {
                            timers.markSentNanos += System.nanoTime() - markStarted;
                        }
                        db.commit();
                        completed += rows.size();
                        batches++;
                    } catch (Exception e) {
                        db.rollback();
                        failures += rows.size();
                        if (failures <= 100) {
                            System.err.printf("relay probe batch failed: service=%s, size=%d, error=%s%n",
                                    config.service(), rows.size(), e.getMessage());
                        }
                    } finally {
                        timers.batchNanos += System.nanoTime() - batchStarted;
                    }
                }
            } finally {
                try {
                    channel.queueDelete(queue);
                } catch (Exception ignored) {
                }
            }
            double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
            return new Result(completed, failures, batches, elapsedSeconds, timers);
        }
    }

    private static List<RelayRow> selectRows(PreparedStatement statement) throws SQLException {
        List<RelayRow> rows = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                rows.add(new RelayRow(rs.getString("relay_id"), rs.getString("payload")));
            }
        }
        return rows;
    }

    private static void printJson(Config config, Result result) {
        System.out.println("{");
        System.out.printf("  \"mode\": \"outboxRelayCeilingProbe\",%n");
        System.out.printf("  \"service\": \"%s\",%n", config.service());
        System.out.printf("  \"marketId\": \"%s\",%n", config.marketId());
        System.out.printf("  \"events\": %d,%n", config.events());
        System.out.printf("  \"batchSize\": %d,%n", config.batchSize());
        System.out.printf("  \"completed\": %d,%n", result.completed());
        System.out.printf("  \"failures\": %d,%n", result.failures());
        System.out.printf("  \"batches\": %d,%n", result.batches());
        System.out.printf("  \"elapsedSeconds\": %.3f,%n", result.elapsedSeconds());
        System.out.printf("  \"relayTps\": %.2f,%n", result.completed() / Math.max(result.elapsedSeconds(), 0.001));
        System.out.printf("  \"selectSeconds\": %.6f,%n", seconds(result.timers().selectNanos));
        System.out.printf("  \"enqueueSeconds\": %.6f,%n", seconds(result.timers().enqueueNanos));
        System.out.printf("  \"confirmSeconds\": %.6f,%n", seconds(result.timers().confirmNanos));
        System.out.printf("  \"markSentSeconds\": %.6f,%n", seconds(result.timers().markSentNanos));
        System.out.printf("  \"batchSeconds\": %.6f,%n", seconds(result.timers().batchNanos));
        System.out.printf("  \"confirmMeanMs\": %.3f,%n",
                result.completed() == 0 ? 0.0 : result.timers().confirmNanos / 1_000_000.0 / result.completed());
        System.out.printf("  \"markSentMeanMs\": %.3f%n",
                result.completed() == 0 ? 0.0 : result.timers().markSentNanos / 1_000_000.0 / result.completed());
        System.out.println("}");
    }

    private static double seconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    private record RelayRow(String id, String payload) {
    }

    private static class ProbeTimers {
        long selectNanos;
        long enqueueNanos;
        long confirmNanos;
        long markSentNanos;
        long batchNanos;
    }

    private record Result(int completed, int failures, int batches, double elapsedSeconds, ProbeTimers timers) {
    }

    private record Config(
            String service,
            String jdbcUrl,
            String username,
            String password,
            String rabbitHost,
            int rabbitPort,
            String rabbitUsername,
            String rabbitPassword,
            String probeExchange,
            String probeRoutingKey,
            String marketId,
            int events,
            int batchSize,
            long confirmTimeoutMs,
            boolean seedRows) {

        private static Config from(String[] args) {
            return new Config(
                    stringArg(args, "--service", "match"),
                    stringArg(args, "--jdbc-url", ""),
                    stringArg(args, "--username", DEFAULT_USERNAME),
                    stringArg(args, "--password", DEFAULT_PASSWORD),
                    stringArg(args, "--rabbit-host", DEFAULT_RABBIT_HOST),
                    intArg(args, "--rabbit-port", DEFAULT_RABBIT_PORT),
                    stringArg(args, "--rabbit-username", DEFAULT_RABBIT_USERNAME),
                    stringArg(args, "--rabbit-password", DEFAULT_RABBIT_PASSWORD),
                    stringArg(args, "--probe-exchange", DEFAULT_PROBE_EXCHANGE),
                    stringArg(args, "--probe-routing-key", DEFAULT_PROBE_ROUTING_KEY),
                    stringArg(args, "--market-id", "RELAY_CEILING_" + UUID.randomUUID()),
                    intArg(args, "--events", 10_000),
                    intArg(args, "--batch-size", 500),
                    longArg(args, "--confirm-timeout-ms", 5000L),
                    booleanArg(args, "--seed-rows", true));
        }

        private static int intArg(String[] args, String name, int defaultValue) {
            return Integer.parseInt(stringArg(args, name, String.valueOf(defaultValue)));
        }

        private static long longArg(String[] args, String name, long defaultValue) {
            return Long.parseLong(stringArg(args, name, String.valueOf(defaultValue)));
        }

        private static boolean booleanArg(String[] args, String name, boolean defaultValue) {
            return Boolean.parseBoolean(stringArg(args, name, String.valueOf(defaultValue)));
        }

        private static String stringArg(String[] args, String name, String defaultValue) {
            for (int i = 0; i < args.length - 1; i++) {
                if (name.equals(args[i])) {
                    return args[i + 1];
                }
            }
            return defaultValue;
        }
    }

    private record ServiceSpec(
            String name,
            String jdbcUrl,
            String seedSql,
            String selectSql,
            String markSentPrefix,
            String markSentSuffix,
            String selectPatternTemplate) {

        private static ServiceSpec forName(String rawName, String jdbcUrlOverride) {
            String name = rawName.toLowerCase(Locale.ROOT);
            return switch (name) {
                case "match" -> match(jdbcUrlOverride);
                case "order" -> order(jdbcUrlOverride);
                case "wallet", "wallet-settlement" -> walletSettlement(jdbcUrlOverride);
                default -> throw new IllegalArgumentException("Unsupported service: " + rawName);
            };
        }

        private static ServiceSpec match(String jdbcUrlOverride) {
            return new ServiceSpec(
                    "match",
                    defaultJdbc(jdbcUrlOverride, "jdbc:postgresql://localhost:15434/eap_match_db"),
                    """
                    INSERT INTO match_engine.trade_outbox
                        (event_type, aggregate_type, aggregate_id, routing_key, payload,
                         status, attempt_count, next_retry_at, created_at, updated_at)
                    VALUES ('TradeExecutedEvent', 'TRADE', ?, 'trade.executed', ?,
                            'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    """
                    SELECT id::text AS relay_id, payload
                    FROM match_engine.trade_outbox
                    WHERE status = 'PENDING'
                      AND aggregate_id LIKE ?
                      AND next_retry_at <= CURRENT_TIMESTAMP
                    ORDER BY created_at, id
                    LIMIT ?
                    """,
                    """
                    UPDATE match_engine.trade_outbox
                    SET status = 'SENT',
                        next_retry_at = NULL,
                        last_error = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id IN (
                    """,
                    ") AND status = 'PENDING'",
                    "%s-%%");
        }

        private static ServiceSpec order(String jdbcUrlOverride) {
            return new ServiceSpec(
                    "order",
                    defaultJdbc(jdbcUrlOverride, "jdbc:postgresql://localhost:15432/eap_order_db"),
                    """
                    INSERT INTO order_service.order_event_outbox
                        (event_id, aggregate_id, exchange_name, routing_key, message_type, payload,
                         status, attempt_count, next_retry_at, created_at, updated_at)
                    VALUES (?, ?, 'trade.exchange', 'trade.order.applied',
                            'com.eap.common.event.OrderTradeAppliedEvent', ?,
                            'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    """
                    SELECT id::text AS relay_id, payload
                    FROM order_service.order_event_outbox
                    WHERE status = 'PENDING'
                      AND payload LIKE ?
                      AND next_retry_at <= CURRENT_TIMESTAMP
                    ORDER BY created_at, id
                    LIMIT ?
                    """,
                    """
                    UPDATE order_service.order_event_outbox
                    SET status = 'SENT',
                        published_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP,
                        last_error = NULL,
                        next_retry_at = NULL
                    WHERE id IN (
                    """,
                    ") AND status = 'PENDING'",
                    "%%%s%%");
        }

        private static ServiceSpec walletSettlement(String jdbcUrlOverride) {
            return new ServiceSpec(
                    "wallet-settlement",
                    defaultJdbc(jdbcUrlOverride, "jdbc:postgresql://localhost:15433/eap_wallet_db"),
                    """
                    INSERT INTO wallet_service.trade_settlements
                        (trade_id, legacy_match_id, settled_at,
                         buyer_id, seller_id, buyer_order_id, seller_order_id,
                         deal_price, quantity, buyer_locked_currency, buyer_refund_currency,
                         seller_received_currency, event_status, attempt_count,
                         next_retry_at, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP,
                            ?, ?, ?, ?,
                            100, 1, 100, 0,
                            100, 'PENDING', 0,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (trade_id) DO UPDATE
                    SET event_status = 'PENDING',
                        next_retry_at = CURRENT_TIMESTAMP,
                        last_error = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    """,
                    """
                    SELECT trade_id AS relay_id,
                           ('{"tradeId":"' || trade_id || '","eventType":"WalletTradeSettledEvent"}') AS payload
                    FROM wallet_service.trade_settlements
                    WHERE event_status = 'PENDING'
                      AND trade_id LIKE ?
                      AND next_retry_at <= CURRENT_TIMESTAMP
                    ORDER BY settled_at, trade_id
                    LIMIT ?
                    """,
                    """
                    UPDATE wallet_service.trade_settlements
                    SET event_status = 'SENT',
                        next_retry_at = NULL,
                        last_error = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE trade_id IN (
                    """,
                    ") AND event_status = 'PENDING'",
                    "%s-%%");
        }

        private static String defaultJdbc(String override, String defaultValue) {
            return override == null || override.isBlank() ? defaultValue : override;
        }

        private void bindSeed(PreparedStatement statement, String marketId, int sequence) throws SQLException {
            String id = marketId + "-" + sequence;
            String payload = "{\"id\":\"" + id + "\",\"marketId\":\"" + marketId + "\"}";
            switch (name) {
                case "match" -> {
                    statement.setString(1, id);
                    statement.setString(2, payload);
                }
                case "order" -> {
                    statement.setObject(1, uuid(sequence, 10));
                    statement.setObject(2, uuid(sequence, 11));
                    statement.setString(3, payload);
                }
                case "wallet-settlement" -> {
                    statement.setString(1, id);
                    statement.setInt(2, sequence);
                    statement.setObject(3, uuid(sequence, 1));
                    statement.setObject(4, uuid(sequence, 2));
                    statement.setObject(5, uuid(sequence, 3));
                    statement.setObject(6, uuid(sequence, 4));
                }
                default -> throw new IllegalStateException("Unsupported service: " + name);
            }
        }

        private String selectPattern(String marketId) {
            return String.format(selectPatternTemplate, marketId);
        }

        private String markSentSql(int rows) {
            StringBuilder sql = new StringBuilder(markSentPrefix);
            for (int i = 0; i < rows; i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
            }
            sql.append(markSentSuffix);
            return sql.toString();
        }

        private void bindMarkSent(PreparedStatement statement, List<RelayRow> rows) throws SQLException {
            for (int i = 0; i < rows.size(); i++) {
                String id = rows.get(i).id();
                if ("wallet-settlement".equals(name)) {
                    statement.setString(i + 1, id);
                } else {
                    statement.setLong(i + 1, Long.parseLong(id));
                }
            }
        }

        private static UUID uuid(long sequence, long salt) {
            return new UUID(sequence, salt);
        }
    }
}
