package com.eap.eap_matchengine.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.UUID;

@Component
@Endpoint(id = "orderBookRuntime")
@ConditionalOnProperty(
        name = "eap.match-engine.orderbook-runtime.admin-enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class OrderBookRuntimeEndpoint {

    private final OrderBookRuntimeAdminService admin;

    @ReadOperation
    public Map<String, Object> status() {
        return admin.status();
    }

    @WriteOperation
    public OrderBookRuntimeAdminService.Result control(
            String action,
            String operator,
            String reason,
            @Nullable String manifestId,
            @Nullable Long expectedFenceEpoch,
            @Nullable UUID expectedGeneration,
            @Nullable Long expectedVersion,
            @Nullable Long sourceOrderWatermark,
            @Nullable Long sourceTradeWatermark,
            @Nullable Long expectedOpenOrderCount,
            @Nullable Long expectedOpenQuantity,
            @Nullable String expectedIdentityDigest) {
        if ("INITIALIZE_EMPTY".equalsIgnoreCase(action)) {
            return admin.initializeEmpty(operator, reason);
        }
        if ("ACTIVATE_REBUILT".equalsIgnoreCase(action)) {
            return admin.activateRebuilt(new OrderBookRuntimeAdminService.ActivationRequest(
                    manifestId,
                    expectedFenceEpoch,
                    expectedGeneration,
                    expectedVersion,
                    sourceOrderWatermark,
                    sourceTradeWatermark,
                    expectedOpenOrderCount,
                    expectedOpenQuantity,
                    expectedIdentityDigest,
                    operator,
                    reason));
        }
        throw new IllegalArgumentException("Unsupported order-book runtime action: " + action);
    }
}
