package com.eap.eap_matchengine.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Endpoint(id = "matchOrderAdmissionInbox")
@ConditionalOnProperty(
        name = "eap.match-engine.order-admission-inbox.admin-enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class MatchOrderAdmissionInboxEndpoint {

    private final MatchOrderAdmissionInbox inbox;

    @ReadOperation
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowsByStatus", inbox.countByStatus());
        result.put("identityConflicts", inbox.countConflicts());
        return result;
    }

    @WriteOperation
    public Map<String, Object> retry(UUID orderId) {
        boolean accepted = inbox.retryExhaustedTechnicalFailure(orderId);
        return Map.of("orderId", orderId, "accepted", accepted);
    }
}
