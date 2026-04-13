package com.eap.eap_matchengine.application;

import com.eap.common.dto.AuctionConfigDto;
import com.eap.common.event.AuctionClearedEvent;
import com.eap.common.event.AuctionCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.eap.common.constants.RabbitMQConstants.AUCTION_CLEARED_KEY;
import static com.eap.common.constants.RabbitMQConstants.AUCTION_CREATED_KEY;
import static com.eap.common.constants.RabbitMQConstants.AUCTION_EXCHANGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuctionSchedulerService.
 *
 * Verifies that openAuction() and closeAndClear() respect the auctionEnabled flag
 * from AuctionRedisService.getGlobalConfig(), and that normal execution paths
 * trigger the expected downstream calls.
 */
@ExtendWith(MockitoExtension.class)
class AuctionSchedulerServiceTest {

    @Mock
    private AuctionRedisService auctionRedisService;

    @Mock
    private AuctionClearingService clearingService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    private AuctionSchedulerService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AuctionSchedulerService(auctionRedisService, clearingService, rabbitTemplate, redissonClient);
        // Inject @Value field default
        ReflectionTestUtils.setField(service, "durationMinutes", 10);
    }

    // ==================== openAuction() ====================

    @Nested
    @DisplayName("openAuction() — auctionEnabled flag")
    class OpenAuctionEnabledFlag {

        @Test
        @DisplayName("returns immediately without acquiring lock when auctionEnabled=false")
        void openAuction_disabled_returnsImmediately() {
            when(auctionRedisService.getGlobalConfig()).thenReturn(disabledConfig());

            service.openAuction();

            verify(redissonClient, never()).getLock(anyString());
            verify(auctionRedisService, never()).initAuction(any(), any(), any(), any(), any());
            verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        }

        @Test
        @DisplayName("acquires lock and publishes AuctionCreatedEvent when auctionEnabled=true")
        void openAuction_enabled_publishesCreatedEvent() throws Exception {
            AuctionConfigDto config = enabledConfig();
            // getGlobalConfig() is called twice: once for the enabled check, once inside the lock
            when(auctionRedisService.getGlobalConfig()).thenReturn(config);
            when(redissonClient.getLock("auction:scheduler:open")).thenReturn(rLock);
            when(rLock.tryLock(5, 30, TimeUnit.SECONDS)).thenReturn(true);
            when(rLock.isHeldByCurrentThread()).thenReturn(true);

            service.openAuction();

            verify(auctionRedisService).initAuction(
                    argThat(id -> id.startsWith("AUC-")),
                    any(), any(),
                    eq(config.getPriceFloor()),
                    eq(config.getPriceCeiling())
            );

            ArgumentCaptor<AuctionCreatedEvent> eventCaptor = ArgumentCaptor.forClass(AuctionCreatedEvent.class);
            verify(rabbitTemplate).convertAndSend(eq(AUCTION_EXCHANGE), eq(AUCTION_CREATED_KEY), eventCaptor.capture());

            AuctionCreatedEvent published = eventCaptor.getValue();
            assertThat(published.getAuctionId()).startsWith("AUC-");
            assertThat(published.getPriceFloor()).isEqualTo(config.getPriceFloor());
            assertThat(published.getPriceCeiling()).isEqualTo(config.getPriceCeiling());
        }

        @Test
        @DisplayName("does not publish event when lock cannot be acquired")
        void openAuction_lockNotAcquired_noPublish() throws Exception {
            when(auctionRedisService.getGlobalConfig()).thenReturn(enabledConfig());
            when(redissonClient.getLock("auction:scheduler:open")).thenReturn(rLock);
            when(rLock.tryLock(5, 30, TimeUnit.SECONDS)).thenReturn(false);

            service.openAuction();

            verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        }
    }

    // ==================== closeAndClear() ====================

    @Nested
    @DisplayName("closeAndClear() — auctionEnabled flag")
    class CloseAndClearEnabledFlag {

        @Test
        @DisplayName("returns immediately without acquiring lock when auctionEnabled=false")
        void closeAndClear_disabled_returnsImmediately() {
            when(auctionRedisService.getGlobalConfig()).thenReturn(disabledConfig());

            service.closeAndClear();

            verify(redissonClient, never()).getLock(anyString());
            verify(auctionRedisService, never()).closeGate(anyString());
            verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        }

        @Test
        @DisplayName("runs full clearing flow and publishes AuctionClearedEvent when auctionEnabled=true")
        void closeAndClear_enabled_publishesClearedEvent() throws Exception {
            when(auctionRedisService.getGlobalConfig()).thenReturn(enabledConfig());
            when(redissonClient.getLock("auction:scheduler:clear")).thenReturn(rLock);
            when(rLock.tryLock(5, 60, TimeUnit.SECONDS)).thenReturn(true);
            when(rLock.isHeldByCurrentThread()).thenReturn(true);

            String auctionId = "AUC-2026041310";
            when(auctionRedisService.getCurrentAuctionId()).thenReturn(auctionId);
            when(auctionRedisService.getAllBids(auctionId)).thenReturn(
                    new AuctionRedisService.AuctionBids(List.of(), List.of())
            );

            AuctionClearingService.ClearingResult result = mock(AuctionClearingService.ClearingResult.class);
            when(result.getClearingPrice()).thenReturn(55);
            when(result.getClearingVolume()).thenReturn(100);
            when(result.getStatus()).thenReturn("CLEARED");
            when(result.getResults()).thenReturn(List.of());
            when(clearingService.clear(any(), any())).thenReturn(result);

            service.closeAndClear();

            verify(auctionRedisService).closeGate(auctionId);
            verify(clearingService).clear(any(), any());
            verify(auctionRedisService).cleanupAuction(auctionId);

            ArgumentCaptor<AuctionClearedEvent> eventCaptor = ArgumentCaptor.forClass(AuctionClearedEvent.class);
            verify(rabbitTemplate).convertAndSend(eq(AUCTION_EXCHANGE), eq(AUCTION_CLEARED_KEY), eventCaptor.capture());

            AuctionClearedEvent published = eventCaptor.getValue();
            assertThat(published.getAuctionId()).isEqualTo(auctionId);
            assertThat(published.getClearingPrice()).isEqualTo(55);
            assertThat(published.getClearingVolume()).isEqualTo(100);
            assertThat(published.getStatus()).isEqualTo("CLEARED");
        }

        @Test
        @DisplayName("does not publish event when lock cannot be acquired")
        void closeAndClear_lockNotAcquired_noPublish() throws Exception {
            when(auctionRedisService.getGlobalConfig()).thenReturn(enabledConfig());
            when(redissonClient.getLock("auction:scheduler:clear")).thenReturn(rLock);
            when(rLock.tryLock(5, 60, TimeUnit.SECONDS)).thenReturn(false);

            service.closeAndClear();

            verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        }

        @Test
        @DisplayName("skips clearing when no active auction exists")
        void closeAndClear_noActiveAuction_skips() throws Exception {
            when(auctionRedisService.getGlobalConfig()).thenReturn(enabledConfig());
            when(redissonClient.getLock("auction:scheduler:clear")).thenReturn(rLock);
            when(rLock.tryLock(5, 60, TimeUnit.SECONDS)).thenReturn(true);
            when(rLock.isHeldByCurrentThread()).thenReturn(true);
            when(auctionRedisService.getCurrentAuctionId()).thenReturn(null);

            service.closeAndClear();

            verify(clearingService, never()).clear(any(), any());
            verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        }

        @Test
        @DisplayName("publishes FAILED event when clearing throws exception")
        void closeAndClear_clearingException_publishesFailedEvent() throws Exception {
            when(auctionRedisService.getGlobalConfig()).thenReturn(enabledConfig());
            when(redissonClient.getLock("auction:scheduler:clear")).thenReturn(rLock);
            when(rLock.tryLock(5, 60, TimeUnit.SECONDS)).thenReturn(true);
            when(rLock.isHeldByCurrentThread()).thenReturn(true);

            String auctionId = "AUC-2026041310";
            when(auctionRedisService.getCurrentAuctionId()).thenReturn(auctionId);
            when(auctionRedisService.getAllBids(auctionId)).thenReturn(
                    new AuctionRedisService.AuctionBids(List.of(), List.of())
            );
            when(clearingService.clear(any(), any())).thenThrow(new RuntimeException("Clearing error"));

            service.closeAndClear();

            ArgumentCaptor<AuctionClearedEvent> eventCaptor = ArgumentCaptor.forClass(AuctionClearedEvent.class);
            verify(rabbitTemplate).convertAndSend(eq(AUCTION_EXCHANGE), eq(AUCTION_CLEARED_KEY), eventCaptor.capture());

            AuctionClearedEvent published = eventCaptor.getValue();
            assertThat(published.getAuctionId()).isEqualTo(auctionId);
            assertThat(published.getStatus()).isEqualTo("FAILED");
            assertThat(published.getClearingPrice()).isEqualTo(0);
            assertThat(published.getClearingVolume()).isEqualTo(0);
        }
    }

    // ==================== Helpers ====================

    private AuctionConfigDto enabledConfig() {
        return AuctionConfigDto.builder()
                .priceFloor(10)
                .priceCeiling(500)
                .durationMinutes(10)
                .auctionEnabled(true)
                .build();
    }

    private AuctionConfigDto disabledConfig() {
        return AuctionConfigDto.builder()
                .priceFloor(10)
                .priceCeiling(500)
                .durationMinutes(10)
                .auctionEnabled(false)
                .build();
    }
}
