package com.eap.eap_matchengine.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Isolated diagnostic for screening reservation cleanup batch sizes before a full-chain A/B run.
 */
public final class ReservationCleanupLoadProbe {

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:15434/eap_match_db";
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin123";

    private ReservationCleanupLoadProbe() {
    }

    public static void main(String[] args) throws Exception {
        ProbeConfig config = ProbeConfig.from(args);
        HikariDataSource dataSource = dataSource(config);
        LettuceConnectionFactory redisConnectionFactory =
                new LettuceConnectionFactory(config.redisHost(), config.redisPort());
        redisConnectionFactory.afterPropertiesSet();

        RedisTemplate<String, String> redisTemplate = redisTemplate(redisConnectionFactory);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<SeedRow> rows = seedRows(config.events());

        try {
            migrate(dataSource);
            requireNoClaimableTasks(jdbc);

            ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
            RedisOrderBookService orderBookService = new RedisOrderBookService(redisTemplate, objectMapper);
            orderBookService.init();
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ReservationCleanupMetrics metrics = new ReservationCleanupMetrics(registry);
            ReservationCleanupWorker worker = new ReservationCleanupWorker(
                    jdbc,
                    orderBookService,
                    metrics,
                    config.batchSize(),
                    3,
                    1,
                    100,
                    30,
                    config.leaseChunkSize());

            seedDatabase(jdbc, rows);
            seedRedis(redisTemplate, rows);

            Result result = run(config, worker, jdbc, orderBookService, registry, rows.get(0).runPrefix());
            Map<String, Object> output = resultMap(config, result);
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            System.out.println(json);
            if (config.output() != null) {
                File outputFile = new File(config.output());
                File parent = outputFile.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, output);
            }

            require(result.completed() == config.events(), "all cleanup tasks must complete");
            require(result.pending() == 0, "no cleanup task may remain pending");
            require(result.processing() == 0, "no cleanup task may remain processing");
            require(result.failed() == 0, "no cleanup task may fail");
            require(result.activeReservations() == 0, "all Redis reservations must be removed");
        } finally {
            cleanupRedis(redisTemplate, rows);
            cleanupDatabaseQuietly(jdbc, rows.get(0).runPrefix());
            redisConnectionFactory.destroy();
            dataSource.close();
        }
    }

    private static Result run(
            ProbeConfig config,
            ReservationCleanupWorker worker,
            JdbcTemplate jdbc,
            RedisOrderBookService orderBookService,
            SimpleMeterRegistry registry,
            String runPrefix) {
        int cleanupCalls = 0;
        int claimed = 0;
        long startedAt = System.nanoTime();
        while (claimed < config.events()) {
            int batchClaimed = worker.cleanupOnce();
            if (batchClaimed == 0) {
                break;
            }
            cleanupCalls++;
            claimed += batchClaimed;
        }
        double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;

        return new Result(
                cleanupCalls,
                claimed,
                countStatus(jdbc, runPrefix, "COMPLETED"),
                countStatus(jdbc, runPrefix, "PENDING"),
                countStatus(jdbc, runPrefix, "PROCESSING"),
                countStatus(jdbc, runPrefix, "FAILED"),
                orderBookService.countActiveReservations(),
                elapsedSeconds,
                timerMeanMillis(registry, "match_engine_reservation_cleanup_claim_duration"),
                timerMeanMillis(registry, "match_engine_reservation_cleanup_redis_duration"),
                timerMeanMillis(registry, "match_engine_reservation_cleanup_mark_completed_duration"),
                timerMeanMillis(registry, "match_engine_reservation_cleanup_batch_duration"),
                timerMaxMillis(registry, "match_engine_reservation_cleanup_batch_duration"),
                counterValue(registry, "match_engine_reservation_cleanup_failed_total"));
    }

    private static Map<String, Object> resultMap(ProbeConfig config, Result result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mode", "reservationCleanupIsolated");
        output.put("evidenceClass", "isolated-diagnostic");
        output.put("events", config.events());
        output.put("batchSize", config.batchSize());
        output.put("leaseChunkSize", config.leaseChunkSize());
        output.put("cleanupCalls", result.cleanupCalls());
        output.put("claimed", result.claimed());
        output.put("completed", result.completed());
        output.put("pending", result.pending());
        output.put("processing", result.processing());
        output.put("failed", result.failed());
        output.put("activeReservations", result.activeReservations());
        output.put("elapsedSeconds", round(result.elapsedSeconds()));
        output.put("cleanupTasksPerSecond", round(result.completed() / Math.max(result.elapsedSeconds(), 0.001)));
        output.put("claimMeanMs", round(result.claimMeanMs()));
        output.put("redisCleanupMeanMs", round(result.redisCleanupMeanMs()));
        output.put("markCompletedMeanMs", round(result.markCompletedMeanMs()));
        output.put("batchMeanMs", round(result.batchMeanMs()));
        output.put("batchMaxMs", round(result.batchMaxMs()));
        output.put("metricFailures", result.metricFailures());
        output.put("correctness", result.completed() == config.events()
                && result.pending() == 0
                && result.processing() == 0
                && result.failed() == 0
                && result.activeReservations() == 0 ? "PASS" : "FAIL");
        output.put("capacityClaimAllowed", false);
        return output;
    }

    private static void migrate(HikariDataSource dataSource) throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        liquibase.afterPropertiesSet();
    }

    private static HikariDataSource dataSource(ProbeConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(1);
        hikari.setPoolName("reservation-cleanup-probe");
        return new HikariDataSource(hikari);
    }

    private static RedisTemplate<String, String> redisTemplate(LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    private static List<SeedRow> seedRows(int events) {
        String runPrefix = "RC-PROBE-" + UUID.randomUUID();
        List<SeedRow> rows = new ArrayList<>(events);
        for (int index = 0; index < events; index++) {
            UUID orderId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            rows.add(new SeedRow(runPrefix, runPrefix + "-" + index, orderId, userId));
        }
        return rows;
    }

    private static void seedDatabase(JdbcTemplate jdbc, List<SeedRow> rows) {
        jdbc.batchUpdate("""
                INSERT INTO match_engine.reservation_cleanup_tasks
                    (trade_id, order_id, user_id, status, next_retry_at)
                VALUES (?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)
                """, rows, 1_000, (statement, row) -> {
            statement.setString(1, row.tradeId());
            statement.setObject(2, row.orderId());
            statement.setObject(3, row.userId());
        });
    }

    private static void seedRedis(RedisTemplate<String, String> redisTemplate, List<SeedRow> rows) {
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (SeedRow row : rows) {
                byte[] orderId = row.orderId().toString().getBytes(StandardCharsets.UTF_8);
                connection.stringCommands().set(bytes("order:" + row.orderId()), bytes("{}"));
                connection.setCommands().sAdd(bytes("user:" + row.userId() + ":orders"), orderId);
                connection.stringCommands().set(
                        bytes("order:reservation:" + row.orderId()),
                        bytes("{\"orderId\":\"" + row.orderId() + "\",\"tradeId\":\""
                                + row.tradeId() + "\"}"));
            }
            return null;
        });
    }

    private static void requireNoClaimableTasks(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject("""
                SELECT count(*)
                FROM match_engine.reservation_cleanup_tasks
                WHERE status IN ('PENDING', 'PROCESSING')
                """, Long.class);
        require(count != null && count == 0,
                "dedicated MatchEngine load-test database contains claimable cleanup tasks");
    }

    private static long countStatus(JdbcTemplate jdbc, String runPrefix, String status) {
        Long count = jdbc.queryForObject("""
                SELECT count(*)
                FROM match_engine.reservation_cleanup_tasks
                WHERE trade_id LIKE ? AND status = ?
                """, Long.class, runPrefix + "-%", status);
        return count == null ? 0 : count;
    }

    private static void cleanupDatabaseQuietly(JdbcTemplate jdbc, String runPrefix) {
        try {
            jdbc.update("DELETE FROM match_engine.reservation_cleanup_tasks WHERE trade_id LIKE ?", runPrefix + "-%");
        } catch (RuntimeException ignored) {
            // Preserve the original setup or probe failure when the schema is unavailable.
        }
    }

    private static void cleanupRedis(RedisTemplate<String, String> redisTemplate, List<SeedRow> rows) {
        List<String> keys = new ArrayList<>(rows.size() * 3);
        for (SeedRow row : rows) {
            keys.add("order:" + row.orderId());
            keys.add("user:" + row.userId() + ":orders");
            keys.add("order:reservation:" + row.orderId());
        }
        redisTemplate.delete(keys);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static double timerMeanMillis(SimpleMeterRegistry registry, String name) {
        Timer timer = registry.find(name).timer();
        return timer == null ? 0.0 : timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static double timerMaxMillis(SimpleMeterRegistry registry, String name) {
        Timer timer = registry.find(name).timer();
        return timer == null ? 0.0 : timer.max(java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static double counterValue(SimpleMeterRegistry registry, String name) {
        Counter counter = registry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record SeedRow(String runPrefix, String tradeId, UUID orderId, UUID userId) {
    }

    private record Result(
            int cleanupCalls,
            int claimed,
            long completed,
            long pending,
            long processing,
            long failed,
            long activeReservations,
            double elapsedSeconds,
            double claimMeanMs,
            double redisCleanupMeanMs,
            double markCompletedMeanMs,
            double batchMeanMs,
            double batchMaxMs,
            double metricFailures) {
    }

    private record ProbeConfig(
            String jdbcUrl,
            String username,
            String password,
            String redisHost,
            int redisPort,
            int events,
            int batchSize,
            int leaseChunkSize,
            String output) {

        private static ProbeConfig from(String[] args) {
            int events = positiveInt(args, "--events", 10_000);
            return new ProbeConfig(
                    stringArg(args, "--jdbc-url", DEFAULT_JDBC_URL),
                    stringArg(args, "--username", DEFAULT_USERNAME),
                    stringArg(args, "--password", DEFAULT_PASSWORD),
                    stringArg(args, "--redis-host", "localhost"),
                    positiveInt(args, "--redis-port", 6379),
                    events,
                    positiveInt(args, "--batch-size", 500),
                    positiveInt(args, "--lease-chunk-size", 50),
                    nullableArg(args, "--output"));
        }

        private static int positiveInt(String[] args, String name, int defaultValue) {
            int value = Integer.parseInt(stringArg(args, name, String.valueOf(defaultValue)));
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }

        private static String stringArg(String[] args, String name, String defaultValue) {
            String value = nullableArg(args, name);
            return value == null ? defaultValue : value;
        }

        private static String nullableArg(String[] args, String name) {
            for (int index = 0; index < args.length - 1; index++) {
                if (name.equals(args[index])) {
                    return args[index + 1];
                }
            }
            return null;
        }
    }
}
