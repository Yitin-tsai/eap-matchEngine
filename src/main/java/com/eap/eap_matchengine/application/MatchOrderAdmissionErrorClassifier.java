package com.eap.eap_matchengine.application;

import org.redisson.client.RedisException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class MatchOrderAdmissionErrorClassifier {

    public Classification classify(Exception failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof MatchOrderAdmissionPrerequisiteNotReadyException) {
                return new Classification(Category.PREREQUISITE, "PREREQUISITE_RECOVERY_PENDING");
            }
            if (current instanceof DataIntegrityViolationException) {
                return new Classification(Category.PERMANENT, "PERMANENT_DATA_INTEGRITY");
            }
            if (current instanceof DataAccessException) {
                return new Classification(Category.TRANSIENT, "TRANSIENT_DATA_STORE");
            }
            if (current instanceof RedisException) {
                return new Classification(Category.TRANSIENT, "TRANSIENT_REDIS");
            }
            if (current instanceof IllegalArgumentException || current instanceof ArithmeticException) {
                return new Classification(Category.PERMANENT, "PERMANENT_INVALID_EVENT");
            }
            current = current.getCause();
        }
        return new Classification(Category.TRANSIENT, "UNKNOWN_RETRYABLE");
    }

    public enum Category {
        TRANSIENT,
        PERMANENT,
        PREREQUISITE
    }

    public record Classification(Category category, String errorType) {
        public boolean retryable() {
            return category != Category.PERMANENT;
        }
    }
}
