package com.eap.eap_matchengine.application;

public enum ReservationCompletionOutcome {
    COMPLETED(1L, true),
    ALREADY_COMPLETED(0L, true),
    ORDER_ID_MISMATCH(-1L, false),
    NEWER_TRADE_OWNER(-2L, false);

    private final long redisCode;
    private final boolean successful;

    ReservationCompletionOutcome(long redisCode, boolean successful) {
        this.redisCode = redisCode;
        this.successful = successful;
    }

    public long redisCode() {
        return redisCode;
    }

    public boolean successful() {
        return successful;
    }

    public static ReservationCompletionOutcome fromRedisCode(long redisCode) {
        for (ReservationCompletionOutcome outcome : values()) {
            if (outcome.redisCode == redisCode) {
                return outcome;
            }
        }
        throw new IllegalStateException("Unexpected reservation completion result: " + redisCode);
    }
}
