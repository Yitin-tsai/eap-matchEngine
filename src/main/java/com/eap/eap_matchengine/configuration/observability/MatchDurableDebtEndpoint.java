package com.eap.eap_matchengine.configuration.observability;

import com.eap.common.observability.DurableDebtSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

@Component
@Endpoint(id = "durableDebt")
@RequiredArgsConstructor
public class MatchDurableDebtEndpoint {

    private final MatchDurableDebtSnapshotProvider provider;

    @ReadOperation
    public DurableDebtSnapshot snapshot() {
        return provider.snapshot();
    }
}
