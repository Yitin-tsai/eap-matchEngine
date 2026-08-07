package com.eap.eap_matchengine.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MatchingEngineMetrics {

    private final Timer tryMatchDuration;
    private final Timer tryMatchAddedToBookDuration;
    private final Timer tryMatchFullyMatchedDuration;
    private final Timer tryMatchMatchedWithRemainderDuration;
    private final Timer tryMatchNoOpDuration;
    private final Timer orderConfirmedListenerDuration;
    private final Timer reserveDuration;
    private final Timer reservePrepareDuration;
    private final Timer reserveCallbackPrepareDuration;
    private final Timer reserveSerializeIncomingDuration;
    private final Timer reserveRedisEvalDuration;
    private final Timer reserveDeserializeRestingDuration;
    private final Timer reserveResultDuration;
    private final Timer addOrderDuration;
    private final Timer matchIdDuration;
    private final Timer tradeRecordDuration;
    private final Timer tradeRecordSerializeDuration;
    private final Timer tradeRecordInsertDuration;
    private final Timer tradeRecordTransactionBodyDuration;
    private final Timer tradeRecordTransactionTotalDuration;
    private final Timer tradeRecordCommitGapDuration;
    private final Timer completeReservationDuration;
    private final Timer completeReservationPrepareDuration;
    private final Timer completeReservationRedisEvalDuration;
    private final Timer completeReservationResultDuration;
    private final Timer releaseReservationDuration;
    private final Counter tradeRecordedTotal;
    private final Counter orderAddedTotal;
    private final Counter reservationCompletedTotal;
    private final Counter reservationReleasedTotal;

    public MatchingEngineMetrics(MeterRegistry registry) {
        this.tryMatchDuration = timer(registry, "match_engine_try_match_duration",
                "Wall-clock time spent handling one OrderConfirmed event");
        this.tryMatchAddedToBookDuration = tryMatchOutcomeTimer(registry, "added_to_book",
                "Wall-clock time spent handling an OrderConfirmed event that was added to the order book without a trade");
        this.tryMatchFullyMatchedDuration = tryMatchOutcomeTimer(registry, "fully_matched",
                "Wall-clock time spent handling an OrderConfirmed event that fully matched into one or more trades");
        this.tryMatchMatchedWithRemainderDuration = tryMatchOutcomeTimer(registry, "matched_with_remainder",
                "Wall-clock time spent handling an OrderConfirmed event that matched at least once and left remaining quantity");
        this.tryMatchNoOpDuration = tryMatchOutcomeTimer(registry, "no_op",
                "Wall-clock time spent handling an OrderConfirmed event that produced no orderbook change");
        this.orderConfirmedListenerDuration = timer(registry, "match_engine_order_confirmed_listener_duration",
                "Wall-clock time spent inside the MatchEngine OrderConfirmed Rabbit listener");
        this.reserveDuration = timer(registry, "match_engine_reserve_order_duration",
                "Time spent reserving the best resting order from Redis");
        this.reservePrepareDuration = reservePhaseTimer(registry, "prepare",
                "Time spent preparing Redis reserve-or-add keys and arguments");
        this.reserveCallbackPrepareDuration = reservePhaseTimer(registry, "callback_prepare",
                "Time spent preparing Redis reserve-or-add byte-array callback arguments");
        this.reserveSerializeIncomingDuration = reservePhaseTimer(registry, "serialize_incoming",
                "Time spent serializing incoming order before Redis reserve-or-add");
        this.reserveRedisEvalDuration = reservePhaseTimer(registry, "redis_eval",
                "Time spent executing Redis reserve-or-add Lua script");
        this.reserveDeserializeRestingDuration = reservePhaseTimer(registry, "deserialize_resting",
                "Time spent deserializing reserved resting order from Redis");
        this.reserveResultDuration = reservePhaseTimer(registry, "result",
                "Time spent interpreting Redis reserve-or-add result");
        this.addOrderDuration = timer(registry, "match_engine_add_order_duration",
                "Time spent adding an unmatched order to the Redis order book");
        this.matchIdDuration = timer(registry, "match_engine_match_id_duration",
                "Time spent generating the distributed match id");
        this.tradeRecordDuration = timer(registry, "match_engine_trade_record_duration",
                "Time spent persisting TradeExecuted and its outbox record");
        this.tradeRecordSerializeDuration = tradeRecordPhaseTimer(registry, "serialize",
                "Time spent serializing TradeExecuted payload before persistence");
        this.tradeRecordInsertDuration = tradeRecordPhaseTimer(registry, "insert_trade_execution_and_outbox",
                "Time spent inserting TradeExecuted fact and outbox row");
        this.tradeRecordTransactionBodyDuration = tradeRecordPhaseTimer(registry, "transaction_body",
                "Time spent inside the TradeExecuted transactional method before commit");
        this.tradeRecordTransactionTotalDuration = tradeRecordPhaseTimer(registry, "transaction_total",
                "Time spent from entering the TradeExecuted transaction method until transaction completion");
        this.tradeRecordCommitGapDuration = tradeRecordPhaseTimer(registry, "commit_gap",
                "Time between TradeExecuted transaction method body completion and transaction completion callback");
        this.completeReservationDuration = timer(registry, "match_engine_complete_reservation_duration",
                "Time spent completing a reserved resting order in Redis");
        this.completeReservationPrepareDuration = completeReservationPhaseTimer(registry, "prepare",
                "Time spent preparing Redis complete-reservation keys and arguments");
        this.completeReservationRedisEvalDuration = completeReservationPhaseTimer(registry, "redis_eval",
                "Time spent executing the complete-reservation Redis Lua script");
        this.completeReservationResultDuration = completeReservationPhaseTimer(registry, "result",
                "Time spent handling the complete-reservation Redis Lua result");
        this.releaseReservationDuration = timer(registry, "match_engine_release_reservation_duration",
                "Time spent releasing a reserved resting order back to Redis");
        this.tradeRecordedTotal = Counter.builder("match_engine_trade_recorded_total")
                .description("Total TradeExecuted records persisted by MatchEngine")
                .register(registry);
        this.orderAddedTotal = Counter.builder("match_engine_order_added_total")
                .description("Total unmatched orders added to the Redis order book")
                .register(registry);
        this.reservationCompletedTotal = Counter.builder("match_engine_reservation_completed_total")
                .description("Total reserved resting orders completed after durable trade persistence")
                .register(registry);
        this.reservationReleasedTotal = Counter.builder("match_engine_reservation_released_total")
                .description("Total reserved resting orders released after trade persistence failure or partial fill")
                .register(registry);
    }

    void recordTryMatch(Duration duration) {
        tryMatchDuration.record(duration);
    }

    void recordTryMatchOutcome(String outcome, Duration duration) {
        switch (outcome) {
            case "added_to_book" -> tryMatchAddedToBookDuration.record(duration);
            case "fully_matched" -> tryMatchFullyMatchedDuration.record(duration);
            case "matched_with_remainder" -> tryMatchMatchedWithRemainderDuration.record(duration);
            default -> tryMatchNoOpDuration.record(duration);
        }
    }

    void recordOrderConfirmedListener(Duration duration) {
        orderConfirmedListenerDuration.record(duration);
    }

    void recordReserve(Duration duration) {
        reserveDuration.record(duration);
    }

    void recordReservePrepare(Duration duration) {
        reservePrepareDuration.record(duration);
    }

    void recordReserveCallbackPrepare(Duration duration) {
        reserveCallbackPrepareDuration.record(duration);
    }

    void recordReserveSerializeIncoming(Duration duration) {
        reserveSerializeIncomingDuration.record(duration);
    }

    void recordReserveRedisEval(Duration duration) {
        reserveRedisEvalDuration.record(duration);
    }

    void recordReserveDeserializeResting(Duration duration) {
        reserveDeserializeRestingDuration.record(duration);
    }

    void recordReserveResult(Duration duration) {
        reserveResultDuration.record(duration);
    }

    void recordAddOrder(Duration duration) {
        addOrderDuration.record(duration);
    }

    void recordMatchId(Duration duration) {
        matchIdDuration.record(duration);
    }

    void recordTradeRecord(Duration duration) {
        tradeRecordDuration.record(duration);
    }

    void recordTradeRecordSerialize(Duration duration) {
        tradeRecordSerializeDuration.record(duration);
    }

    void recordTradeRecordInsert(Duration duration) {
        tradeRecordInsertDuration.record(duration);
    }

    void recordTradeRecordTransactionBody(Duration duration) {
        tradeRecordTransactionBodyDuration.record(duration);
    }

    void recordTradeRecordTransactionTotal(Duration duration) {
        tradeRecordTransactionTotalDuration.record(duration);
    }

    void recordTradeRecordCommitGap(Duration duration) {
        tradeRecordCommitGapDuration.record(duration);
    }

    void recordCompleteReservation(Duration duration) {
        completeReservationDuration.record(duration);
    }

    void recordCompleteReservationPrepare(Duration duration) {
        completeReservationPrepareDuration.record(duration);
    }

    void recordCompleteReservationRedisEval(Duration duration) {
        completeReservationRedisEvalDuration.record(duration);
    }

    void recordCompleteReservationResult(Duration duration) {
        completeReservationResultDuration.record(duration);
    }

    void recordReleaseReservation(Duration duration) {
        releaseReservationDuration.record(duration);
    }

    void tradeRecorded() {
        tradeRecordedTotal.increment();
    }

    void orderAdded() {
        orderAddedTotal.increment();
    }

    void reservationCompleted() {
        reservationCompletedTotal.increment();
    }

    void reservationReleased() {
        reservationReleasedTotal.increment();
    }

    private Timer timer(MeterRegistry registry, String name, String description) {
        return Timer.builder(name)
                .description(description)
                .publishPercentileHistogram()
                .register(registry);
    }

    private Timer tradeRecordPhaseTimer(MeterRegistry registry, String phase, String description) {
        return Timer.builder("match_engine_trade_record_phase_duration")
                .tag("phase", phase)
                .description(description)
                .publishPercentileHistogram()
                .register(registry);
    }

    private Timer reservePhaseTimer(MeterRegistry registry, String phase, String description) {
        return Timer.builder("match_engine_reserve_order_phase_duration")
                .tag("phase", phase)
                .description(description)
                .publishPercentileHistogram()
                .register(registry);
    }

    private Timer completeReservationPhaseTimer(MeterRegistry registry, String phase, String description) {
        return Timer.builder("match_engine_complete_reservation_phase_duration")
                .tag("phase", phase)
                .description(description)
                .publishPercentileHistogram()
                .register(registry);
    }

    private Timer tryMatchOutcomeTimer(MeterRegistry registry, String outcome, String description) {
        return Timer.builder("match_engine_try_match_outcome_duration")
                .tag("outcome", outcome)
                .description(description)
                .publishPercentileHistogram()
                .register(registry);
    }
}
