package com.eap.eap_matchengine.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Explicit, non-destructive full-manifest diagnostic for a caller-proven quiescent
 * runtime. Ordinary readiness/status deliberately does not inspect transient book
 * structure while matching is active.
 */
@Component
@Endpoint(id = "orderBookRuntimeManifest")
@ConditionalOnProperty(
        name = "eap.match-engine.orderbook-runtime.admin-enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class OrderBookRuntimeManifestEndpoint {

    private final OrderBookRuntimeAdminService admin;

    @ReadOperation
    public Map<String, Object> inspect() {
        return admin.inspectReadyManifest();
    }
}
