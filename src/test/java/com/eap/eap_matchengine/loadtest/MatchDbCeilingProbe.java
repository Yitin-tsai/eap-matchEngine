package com.eap.eap_matchengine.loadtest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MatchDbCeilingProbe {

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:15434/eap_match_db";
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin123";
    private static final String CTE_METADATA_SQL = """
            WITH inserted_trade AS (
                INSERT INTO match_engine.trade_executions
                    (trade_id, sequence, legacy_match_id, market_id,
                     buyer_id, seller_id, buyer_order_id, seller_order_id,
                     buyer_market_sequence, seller_market_sequence,
                     origin_buyer_price, origin_seller_price, deal_price, quantity, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (trade_id) DO NOTHING
                RETURNING trade_id
            )
            INSERT INTO match_engine.trade_outbox
                (event_type, aggregate_type, aggregate_id, routing_key)
            SELECT ?, ?, inserted_trade.trade_id, ?
            FROM inserted_trade
            """;
    private static final String TRADE_ONLY_SQL = """
            INSERT INTO match_engine.trade_executions
                (trade_id, sequence, legacy_match_id, market_id,
                 buyer_id, seller_id, buyer_order_id, seller_order_id,
                 buyer_market_sequence, seller_market_sequence,
                 origin_buyer_price, origin_seller_price, deal_price, quantity, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (trade_id) DO NOTHING
            """;
    private static final String OUTBOX_METADATA_SQL = """
            INSERT INTO match_engine.trade_outbox
                (event_type, aggregate_type, aggregate_id, routing_key)
            VALUES (?, ?, ?, ?)
            """;

    public static void main(String[] args) throws Exception {
        Config config = Config.from(args);
        Result result = run(config);
        printJson(config, result);
        if (result.failures() > 0) {
            throw new IllegalStateException("DB ceiling probe failed rows=" + result.failures());
        }
    }

    private static Result run(Config config) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(config.workers());
        CountDownLatch done = new CountDownLatch(config.events());
        Semaphore inFlight = new Semaphore(config.workers() * Math.max(config.batchSize(), 1));
        AtomicInteger next = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>(config.events()));

        long started = System.nanoTime();
        for (int worker = 0; worker < config.workers(); worker++) {
            executor.execute(() -> runWorker(config, next, done, inFlight, failures, latenciesNanos));
        }
        done.await();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;

        List<Long> sorted = new ArrayList<>(latenciesNanos);
        Collections.sort(sorted);
        return new Result(
                config.events() - failures.get(),
                failures.get(),
                elapsedSeconds,
                percentileMillis(sorted, 0.50),
                percentileMillis(sorted, 0.95),
                percentileMillis(sorted, 0.99));
    }

    private static void runWorker(
            Config config,
            AtomicInteger next,
            CountDownLatch done,
            Semaphore inFlight,
            AtomicInteger failures,
            List<Long> latenciesNanos) {
        try (Connection connection = DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password());
             PreparedStatement cteStatement = config.shape() == Shape.CTE_METADATA
                     ? connection.prepareStatement(CTE_METADATA_SQL)
                     : null;
             PreparedStatement tradeStatement = config.shape() != Shape.CTE_METADATA
                     ? connection.prepareStatement(TRADE_ONLY_SQL)
                     : null;
             PreparedStatement outboxStatement = config.shape() == Shape.SPLIT_METADATA
                     ? connection.prepareStatement(OUTBOX_METADATA_SQL)
                     : null) {
            connection.setAutoCommit(config.mode() == Mode.AUTOCOMMIT);
            int uncommitted = 0;
            while (true) {
                int index = next.getAndIncrement();
                if (index >= config.events()) {
                    commitIfNeeded(connection, config, uncommitted);
                    return;
                }
                inFlight.acquire();
                long rowStarted = System.nanoTime();
                try {
                    long sequence = index + 1L;
                    int inserted = executeShape(config, cteStatement, tradeStatement, outboxStatement, sequence);
                    if (inserted != 1) {
                        throw new IllegalStateException("unexpected inserted rows=" + inserted);
                    }
                    uncommitted++;
                    if (config.mode() == Mode.TRANSACTION_PER_ROW
                            || (config.mode() == Mode.GROUPED_TRANSACTION && uncommitted >= config.batchSize())) {
                        connection.commit();
                        uncommitted = 0;
                    }
                    latenciesNanos.add(System.nanoTime() - rowStarted);
                } catch (Exception e) {
                    failures.incrementAndGet();
                    rollbackQuietly(connection, config);
                    uncommitted = 0;
                    if (failures.get() <= 10) {
                        System.err.printf("probe row failed: index=%d, error=%s%n", index, e.getMessage());
                    }
                } finally {
                    inFlight.release();
                    done.countDown();
                }
            }
        } catch (Exception e) {
            int remaining;
            do {
                remaining = next.getAndIncrement();
                if (remaining < config.events()) {
                    failures.incrementAndGet();
                    done.countDown();
                }
            } while (remaining < config.events());
            System.err.printf("probe worker failed: %s%n", e.getMessage());
        }
    }

    private static int executeShape(
            Config config,
            PreparedStatement cteStatement,
            PreparedStatement tradeStatement,
            PreparedStatement outboxStatement,
            long sequence) throws SQLException {
        return switch (config.shape()) {
            case CTE_METADATA -> {
                bindTradeAndOutboxCte(cteStatement, config.marketId(), sequence);
                yield cteStatement.executeUpdate();
            }
            case SPLIT_METADATA -> {
                bindTrade(tradeStatement, config.marketId(), sequence);
                int insertedTrade = tradeStatement.executeUpdate();
                if (insertedTrade == 1) {
                    bindOutbox(outboxStatement, config.marketId(), sequence);
                    outboxStatement.executeUpdate();
                }
                yield insertedTrade;
            }
            case TRADE_ONLY -> {
                bindTrade(tradeStatement, config.marketId(), sequence);
                yield tradeStatement.executeUpdate();
            }
        };
    }

    private static void bindTradeAndOutboxCte(
            PreparedStatement statement,
            String marketId,
            long sequence) throws SQLException {
        bindTrade(statement, marketId, sequence);
        statement.setString(16, "TradeExecutedEvent");
        statement.setString(17, "TRADE");
        statement.setString(18, "trade.executed");
    }

    private static void bindTrade(PreparedStatement statement, String marketId, long sequence) throws SQLException {
        String tradeId = marketId + "-" + sequence;
        UUID buyerId = uuid(sequence, 1);
        UUID sellerId = uuid(sequence, 2);
        UUID buyerOrderId = uuid(sequence, 3);
        UUID sellerOrderId = uuid(sequence, 4);
        Timestamp occurredAt = Timestamp.valueOf(LocalDateTime.now());

        statement.setString(1, tradeId);
        statement.setLong(2, sequence);
        statement.setLong(3, sequence);
        statement.setString(4, marketId);
        statement.setObject(5, buyerId);
        statement.setObject(6, sellerId);
        statement.setObject(7, buyerOrderId);
        statement.setObject(8, sellerOrderId);
        statement.setLong(9, sequence);
        statement.setLong(10, sequence);
        statement.setInt(11, 100);
        statement.setInt(12, 100);
        statement.setInt(13, 100);
        statement.setInt(14, 1);
        statement.setTimestamp(15, occurredAt);
    }

    private static void bindOutbox(PreparedStatement statement, String marketId, long sequence) throws SQLException {
        statement.setString(1, "TradeExecutedEvent");
        statement.setString(2, "TRADE");
        statement.setString(3, marketId + "-" + sequence);
        statement.setString(4, "trade.executed");
    }

    private static UUID uuid(long sequence, long salt) {
        return new UUID(sequence, salt);
    }

    private static void commitIfNeeded(Connection connection, Config config, int uncommitted) throws SQLException {
        if (config.mode() != Mode.AUTOCOMMIT && uncommitted > 0) {
            connection.commit();
        }
    }

    private static void rollbackQuietly(Connection connection, Config config) {
        if (config.mode() == Mode.AUTOCOMMIT) {
            return;
        }
        try {
            connection.rollback();
        } catch (Exception ignored) {
        }
    }

    private static double percentileMillis(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))) / 1_000_000.0;
    }

    private static void printJson(Config config, Result result) {
        System.out.println("{");
        System.out.printf("  \"mode\": \"matchDbCeilingProbe\",%n");
        System.out.printf("  \"shape\": \"%s\",%n", config.shape().name().toLowerCase());
        System.out.printf("  \"transactionMode\": \"%s\",%n", config.mode().name().toLowerCase());
        System.out.printf("  \"marketId\": \"%s\",%n", config.marketId());
        System.out.printf("  \"events\": %d,%n", config.events());
        System.out.printf("  \"workers\": %d,%n", config.workers());
        System.out.printf("  \"batchSize\": %d,%n", config.batchSize());
        System.out.printf("  \"completed\": %d,%n", result.completed());
        System.out.printf("  \"failures\": %d,%n", result.failures());
        System.out.printf("  \"elapsedSeconds\": %.3f,%n", result.elapsedSeconds());
        System.out.printf("  \"tradeInsertOutboxTps\": %.2f,%n", result.completed() / Math.max(result.elapsedSeconds(), 0.001));
        System.out.printf("  \"p50Ms\": %.3f,%n", result.p50Ms());
        System.out.printf("  \"p95Ms\": %.3f,%n", result.p95Ms());
        System.out.printf("  \"p99Ms\": %.3f%n", result.p99Ms());
        System.out.println("}");
    }

    private enum Mode {
        AUTOCOMMIT,
        TRANSACTION_PER_ROW,
        GROUPED_TRANSACTION
    }

    private enum Shape {
        CTE_METADATA,
        SPLIT_METADATA,
        TRADE_ONLY
    }

    private record Result(
            int completed,
            int failures,
            double elapsedSeconds,
            double p50Ms,
            double p95Ms,
            double p99Ms) {
    }

    private record Config(
            String jdbcUrl,
            String username,
            String password,
            String marketId,
            int events,
            int workers,
            int batchSize,
            Shape shape,
            Mode mode) {

        private static Config from(String[] args) {
            Mode mode = Mode.valueOf(stringArg(args, "--mode", "transaction_per_row").toUpperCase());
            Shape shape = Shape.valueOf(stringArg(args, "--shape", "cte_metadata").toUpperCase());
            return new Config(
                    stringArg(args, "--jdbc-url", DEFAULT_JDBC_URL),
                    stringArg(args, "--username", DEFAULT_USERNAME),
                    stringArg(args, "--password", DEFAULT_PASSWORD),
                    stringArg(args, "--market-id", "MATCH_DB_CEILING_" + UUID.randomUUID()),
                    intArg(args, "--events", 10_000),
                    intArg(args, "--workers", 16),
                    intArg(args, "--batch-size", 100),
                    shape,
                    mode);
        }

        private static int intArg(String[] args, String name, int defaultValue) {
            return Integer.parseInt(stringArg(args, name, String.valueOf(defaultValue)));
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
}
