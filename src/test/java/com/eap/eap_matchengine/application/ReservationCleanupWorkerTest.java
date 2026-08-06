package com.eap.eap_matchengine.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationCleanupWorkerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private RedisOrderBookService orderBookService;
    @Mock
    private ReservationCleanupMetrics metrics;

    private ReservationCleanupWorker worker;

    @BeforeEach
    void setUp() {
        worker = new ReservationCleanupWorker(
                jdbcTemplate,
                orderBookService,
                metrics,
                100,
                10,
                100,
                30_000,
                30,
                2);
    }

    @Test
    void cleanupOnce_marksSuccessfulRedisCleanupsWithOneDatabaseUpdate() {
        ReservationCleanupWorker.CleanupRow first = cleanupRow(1L);
        ReservationCleanupWorker.CleanupRow second = cleanupRow(2L);
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<ReservationCleanupWorker.CleanupRow>>any(),
                eq(30L),
                eq(100)))
                .thenReturn(List.of(first, second));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);

        int claimed = worker.cleanupOnce();

        org.assertj.core.api.Assertions.assertThat(claimed).isEqualTo(2);
        verify(orderBookService, times(2)).completeReservedOrder(any());
        verify(jdbcTemplate, times(2)).update(anyString(), any(Object[].class));
        verify(metrics).completed(2);
    }

    @Test
    void cleanupOnce_renewsAndCompletesEachChunkBeforeProcessingTheNextChunk() {
        List<ReservationCleanupWorker.CleanupRow> tasks = List.of(
                cleanupRow(1L), cleanupRow(2L), cleanupRow(3L));
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<ReservationCleanupWorker.CleanupRow>>any(),
                eq(30L),
                eq(100)))
                .thenReturn(tasks);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(2, 2, 1, 1);

        worker.cleanupOnce();

        verify(jdbcTemplate, times(4)).update(anyString(), any(Object[].class));
        verify(metrics).completed(3);
    }

    @Test
    void markCompleted_usesOneStatementForTheWholeBatch() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(3);

        worker.markCompleted(List.of(11L, 12L, 13L));

        verify(jdbcTemplate).update(anyString(), eq(new Object[]{11L, 12L, 13L}));
    }

    @Test
    void renewLeases_partialUpdateShouldFailBeforeRedisCleanup() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> worker.renewLeases(List.of(11L, 12L)));
    }

    @Test
    void markCompleted_partialUpdateShouldFailForIdempotentReclaim() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);

        assertThrows(IllegalStateException.class,
                () -> worker.markCompleted(List.of(11L, 12L, 13L)));
    }

    @Test
    void cleanupOnce_whenRedisCleanupFailsAfterDurableCommit_shouldScheduleRetry() {
        ReservationCleanupWorker.CleanupRow task = cleanupRow(11L);
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<ReservationCleanupWorker.CleanupRow>>any(),
                eq(30L),
                eq(100)))
                .thenReturn(List.of(task));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(orderBookService).completeReservedOrder(any());

        int claimed = worker.cleanupOnce();

        org.assertj.core.api.Assertions.assertThat(claimed).isEqualTo(1);
        verify(metrics).failed();
        verify(metrics).retryScheduled();
        verify(metrics).completed(0);
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("SET status = 'PENDING'"),
                eq(1),
                any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.contains("redis unavailable"),
                any(LocalDateTime.class),
                eq(11L));
    }

    private ReservationCleanupWorker.CleanupRow cleanupRow(long id) {
        return new ReservationCleanupWorker.CleanupRow(
                id,
                "ENERGY-SPOT-" + id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                0);
    }
}
