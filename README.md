# EAP MatchEngine

`eap-matchEngine` owns CDA price-time matching, the Redis order book, and the append-only `TradeExecuted` fact. It also owns TDA bid collection, auction scheduling, clearing-price calculation, and result publication.

## Current Flow

```text
OrderConfirmedEvent
  -> atomic Redis Lua reserve/match operation
  -> persist TradeExecuted + trade_outbox + reservation_cleanup_task
     in one PostgreSQL transaction
  -> TradeOutboxRelay publishes TradeExecutedEvent
     to independent Order and Wallet queues
  -> ReservationCleanupWorker finalizes the matched resting order in Redis
```

If no trade is found, the confirmed order remains in the Redis order book. A reservation reconciler repairs stale Redis reservations after crashes by comparing them with durable trade facts.

TDA follows a separate scheduled flow:

```text
auction schedule
  -> publish AuctionCreatedEvent
AuctionBidConfirmedEvent
  -> collect the Wallet-approved bid in the Redis auction store
scheduled clearing
  -> clear collected bids
  -> publish AuctionClearedEvent to Order and Wallet
```

`AuctionCreatedEvent` and `AuctionClearedEvent` are currently published directly rather than through the trade outbox. A scheduler lock prevents two active nodes from intentionally running the same auction job, but it does not make the direct publication durable. TDA is not covered by the CDA full-lifecycle TPS and recovery claims.

## Ownership

| Owns | Does not own |
| --- | --- |
| Redis order book and price-time decision | Wallet validation or settlement |
| Matching sequence and `TradeExecuted` fact | Order lifecycle projection |
| Trade outbox and retry state | Order/Wallet completion callbacks or a completion view |
| Deferred Redis reservation cleanup and reconciliation | AI or API aggregation |
| TDA bid collection, scheduling and clearing result | TDA Wallet settlement or Order result view |

Order and Wallet consume `TradeExecutedEvent` directly and preserve their own durable results. Cross-service completion is verified externally; MatchEngine does not maintain a fourth copy of downstream completion state.

## Reliability

- Redis Lua keeps match/reserve operations atomic within the order book.
- `trade_executions`, `trade_outbox`, and the cleanup task share one database transaction.
- `trade_id` makes repeated trade recording idempotent.
- Trade outbox publication uses publisher confirms and persisted retry state.
- Deferred cleanup has persisted tasks, leases, retry/backoff, and orphan reconciliation. The reconciler defers to active cleanup tasks and only takes over stale reservations without a normal-path owner.
- The TDA scheduler's direct event publication remains a documented reliability gap.

## Current Performance Risk

Trade outbox polling and reservation maintenance now use isolated schedulers; the focused same-seed A/B passed its correctness gate and removed the earlier scheduler-serialization tail. The remaining sustained risk is same-host saturation in the `OrderConfirmed` intake path, where Redis reservation and the durable trade transaction share CPU and database pressure with the rest of the lifecycle. Short-window results must not be promoted over the long-run backlog gate.

## Run

```bash
./gradlew bootRun
```

Default port: `8082`; context path: `/match-engine`.

## Further Reading

- [Trade execution reliability design](docs/trade-execution-reliability-design.md)
- [Market sequencing plan](docs/market-sequencing-plan.md)
- [2026-08-07 mixed HTTP diagnostic](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/benchmarks/2026-08-07-canonical-mixed-http-diagnostic.md)
- [EAP system architecture](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/architecture.md)
