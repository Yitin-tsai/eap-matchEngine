package com.eap.eap_matchengine.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfSystemProperty(named = "eap.integration.crash-recovery", matches = "true")
class RedisGenerationFenceCrashRecoveryPostgresRedisIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @Test
    void persistedSentinelFromPreviousRedisRun_shouldBeRejectedInsideAddOrderLua() throws Exception {
        String oldRunId = runId();
        String sentinel = "READY|7|" + UUID.randomUUID() + "|" + oldRunId;
        String orderId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        assertThat(redis("SET", OrderBookRuntimeGuard.SENTINEL_KEY, sentinel)).isEqualTo("OK");
        assertThat(redis("SAVE")).isEqualTo("OK");

        REDIS.getDockerClient().restartContainerCmd(REDIS.getContainerId())
                .withTimeout(10)
                .exec();
        waitForRedisCli();

        assertThat(runId()).isNotEqualTo(oldRunId);
        assertThat(redis("GET", OrderBookRuntimeGuard.SENTINEL_KEY)).isEqualTo(sentinel);

        String script = new ClassPathResource("lua/add_order.lua")
                .getContentAsString(StandardCharsets.UTF_8);
        String result = redis(
                "EVAL", script, "4",
                "orderbook:RESTART:buy",
                "order:" + orderId,
                "user:" + userId + ":orders",
                OrderBookRuntimeGuard.SENTINEL_KEY,
                orderId,
                "1000000001",
                "{\"i\":\"" + orderId + "\",\"u\":\"" + userId
                        + "\",\"m\":\"RESTART\",\"s\":1,\"p\":1,\"a\":1,\"t\":\"BUY\"}",
                "1",
                sentinel);

        assertThat(result).isEqualTo("-99");
        assertThat(redis("EXISTS", "orderbook:RESTART:buy", "order:" + orderId,
                "user:" + userId + ":orders")).isEqualTo("0");
    }

    private String runId() throws Exception {
        String info = redis("INFO", "server");
        for (String line : info.split("\\R")) {
            if (line.startsWith("run_id:")) {
                return line.substring("run_id:".length());
            }
        }
        throw new IllegalStateException("Redis INFO did not contain run_id");
    }

    private void waitForRedisCli() throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                if ("PONG".equals(redis("PING"))) {
                    return;
                }
            } catch (Exception failure) {
                last = failure;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Redis did not restart", last);
    }

    private String redis(String... command) throws Exception {
        String[] invocation = new String[command.length + 2];
        invocation[0] = "redis-cli";
        invocation[1] = "--raw";
        System.arraycopy(command, 0, invocation, 2, command.length);
        var result = REDIS.execInContainer(invocation);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("redis-cli failed: " + result.getStderr());
        }
        return result.getStdout().strip();
    }
}
