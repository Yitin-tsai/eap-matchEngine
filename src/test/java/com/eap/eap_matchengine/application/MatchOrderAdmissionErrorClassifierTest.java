package com.eap.eap_matchengine.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MatchOrderAdmissionErrorClassifierTest {

    private final MatchOrderAdmissionErrorClassifier classifier =
            new MatchOrderAdmissionErrorClassifier();

    @Test
    void missingOrInvalidOrderDetail_isPermanentInsteadOfUnknownRetryable() {
        MatchOrderAdmissionErrorClassifier.Classification classification = classifier.classify(
                new OrderBookDataInvariantException("Redis orderbook detail missing"));

        assertThat(classification.category())
                .isEqualTo(MatchOrderAdmissionErrorClassifier.Category.PERMANENT);
        assertThat(classification.errorType())
                .isEqualTo("PERMANENT_ORDER_BOOK_DATA_INVARIANT");
    }

    @Test
    void wrappedOrderBookInvariant_isStillPermanent() {
        MatchOrderAdmissionErrorClassifier.Classification classification = classifier.classify(
                new RuntimeException("wrapper",
                        new OrderBookDataInvariantException("invalid resting order")));

        assertThat(classification.category())
                .isEqualTo(MatchOrderAdmissionErrorClassifier.Category.PERMANENT);
    }
}
