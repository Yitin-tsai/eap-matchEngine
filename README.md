# EAP MatchEngine

`eap-matchEngine` owns CDA price-time matching, the Redis order book, and the append-only `TradeExecuted` fact. It also owns TDA bid collection, auction scheduling, clearing-price calculation, and result publication.

## Current Flow

```text
OrderAssetReservationSucceededEvent
  -> persist order_admission_inbox and ACK Rabbit delivery
  -> bounded concurrent lease worker claims durable admission work
  -> atomic Redis Lua reserve/match operation
  -> persist TradeExecuted + trade_outbox + reservation_cleanup_task
     in one PostgreSQL transaction
  -> TradeOutboxRelay publishes TradeExecutedEvent
     to independent Order and Wallet queues
  -> ReservationCleanupWorker finalizes the matched resting order in Redis
```

If no trade is found, the confirmed order remains in the Redis order book. A reservation reconciler repairs stale Redis reservations after crashes by comparing them with durable trade facts. The atomic Redis candidate selection excludes resting orders owned by the incoming user: it preserves those orders, scans to the next price-time eligible counterparty, and does not create a `TradeExecuted` fact when only self-owned liquidity is available.

Cancellation is an asynchronous MatchEngine-owned decision. PostgreSQL keeps its
recoverable decision state, while Redis owns the atomic arbitration boundary. An intent
blocks an order that has not been admitted; for a resting order, cancellation Lua and
matching Lua compete to remove the same ZSET member. Normal order admission does not
query the cancellation table. Pending decisions are reconciled after admission becomes
a durable trade or a visible remainder, then published through the Match outbox. The
decision stores the immutable original amount separately from the exact unmatched amount
removed by Redis, and reconciliation uses leased claims with retry backoff.

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
| Durable cancellation arbitration and exact removed remainder | Order cancellation request acceptance or Wallet asset release |
| Deferred Redis reservation cleanup and reconciliation | AI or API aggregation |
| TDA bid collection, scheduling and clearing result | TDA Wallet settlement or Order result view |

Order and Wallet consume `TradeExecutedEvent` directly and preserve their own durable results. Cross-service completion is verified externally; MatchEngine does not maintain a fourth copy of downstream completion state.

## Reliability

- The PostgreSQL order-admission inbox keeps ACKed work durable, classifies failures, and reclaims expired worker leases.
- Redis Lua keeps match/reserve operations atomic within the order book.
- Self-trade prevention is enforced inside the same Lua candidate-selection boundary; Wallet also rejects an invalid self-trade fact defensively.
- `trade_executions`, `trade_outbox`, and the cleanup task share one database transaction.
- `trade_id` makes repeated trade recording idempotent.
- Trade outbox publication uses publisher confirms and persisted retry state.
- Deferred cleanup has persisted tasks, leases, bounded retry/backoff, and orphan reconciliation. Lua validates the exact order and trade owner before mutation; idempotent absence completes the task, while identity or newer-owner conflicts fail terminally with durable diagnostics. The reconciler defers to active cleanup tasks and only takes over stale reservations without a normal-path owner.
- Cancellation intent, order admission, and visible-order removal share Redis atomic boundaries; the durable result is published through the existing outbox relay.
- A future Redis-generation readiness gate must pause admission and cancellation consumers before rebuilding volatile matching state. Automatic full-book rebuild is not implemented in the current scope.
- The TDA scheduler's direct event publication remains a documented reliability gap.

## Current Performance Risk

Trade outbox polling and reservation maintenance now use isolated schedulers; the focused same-seed A/B passed its correctness gate and removed the earlier scheduler-serialization tail. Later isolated probes showed that the real RabbitMQ listener, Redis matching, durable trade write, cleanup, trade relay, and downstream fanout each clear the current full-chain rate when measured without the rest of the lifecycle. The remaining risk is integrated same-host contention across HTTP admission, reservation, matching, relays, settlement, databases, broker, JVMs, monitoring, and the load generator. These probes reject a standalone MatchEngine ceiling; they do not establish production capacity or justify increasing concurrency by itself.

The durable admission inbox adds PostgreSQL insert, claim, and terminal-update writes to
every admitted order. Results measured before this change are historical evidence for
their commits, not current-worktree capacity. The Rabbit-to-Match and full-chain runners
require all workload inbox rows to be `APPLIED` with zero non-applied debt. The
2026-09-03 long-window campaign additionally showed Match trades staying current while
Order reservation-result debt accumulated at 300/400 orders/s; the present integrated
bottleneck is therefore downstream Order state application, not evidence of a Match
ceiling.

## Run

```bash
./gradlew bootRun
```

Default port: `8082`; context path: `/match-engine`.

## Further Reading

- [Trade execution reliability design](docs/trade-execution-reliability-design.md)
- [Market sequencing plan](docs/market-sequencing-plan.md)
- [2026-08-14 canonical mixed short-window boundary](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/benchmarks/2026-08-14-canonical-mixed-short-window-boundary.md)
- [2026-08-14 RabbitMQ-to-Match isolated diagnostic](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/benchmarks/2026-08-14-rabbit-match-intake-isolated.md)
- [2026-08-07 scheduler-isolation diagnostic](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/benchmarks/2026-08-07-canonical-mixed-http-diagnostic.md)
- [2026-09-03 current-version full-chain diagnostic](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/benchmarks/2026-09-03-current-version-full-chain.md)
- [EAP system architecture](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/architecture.md)
