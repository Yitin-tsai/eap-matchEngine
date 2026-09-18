package com.eap.eap_matchengine.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadTestTradeOutboxPostConfirmPauseTest {

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void writesMachineReadableMarkerAfterConfirmAndFiresOnlyOnce() throws Exception {
        Path marker = temporaryDirectory.resolve("post-confirm.json");
        LoadTestTradeOutboxPostConfirmPause probe =
                new LoadTestTradeOutboxPostConfirmPause(1, marker.toString());

        probe.afterBrokerConfirmationBeforeMarkSent(List.of(11L, 12L));
        JsonNode first = objectMapper.readTree(marker.toFile());
        probe.afterBrokerConfirmationBeforeMarkSent(List.of(99L));
        JsonNode second = objectMapper.readTree(marker.toFile());

        assertThat(first.path("processId").asLong()).isPositive();
        assertThat(first.path("confirmedCount").asInt()).isEqualTo(2);
        assertThat(first.path("confirmedOutboxIds").isArray()).isTrue();
        assertThat(first.path("confirmedOutboxIds").size()).isEqualTo(2);
        assertThat(first.path("confirmedOutboxIds").get(0).asLong()).isEqualTo(11L);
        assertThat(second).isEqualTo(first);
        assertThat(Files.exists(marker.resolveSibling("post-confirm.json.tmp"))).isFalse();
    }

    @Test
    void rejectsUnsafeConfiguration() {
        assertThatThrownBy(() -> new LoadTestTradeOutboxPostConfirmPause(0, "marker.json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoadTestTradeOutboxPostConfirmPause(1, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
