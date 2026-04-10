# Design Decisions: Timed Double Auction

## D1: Auction Flow Architecture - Separate Path from CDA

**Decision**: Auction bids flow through a completely separate path from CDA orders. They do NOT reuse the existing OrderSubmittedEvent -> wallet check -> OrderConfirmedEvent -> MatchEngine pipeline.

**Reason**: The existing CDA flow is real-time (order -> wallet validate -> confirm -> match instantly). The auction flow is batch-oriented (collect bids -> gate closure -> batch clear). Mixing them in the same pipeline would require complex conditional logic throughout 4 modules. A separate event path (AuctionBidSubmittedEvent, AuctionClearedEvent) on a dedicated exchange (auction.exchange) is cleaner and avoids regression risk on CDA.

**Trade-off**: Some duplication in wallet locking logic (CreateOrderListener for CDA vs AuctionBidListener for auction). Acceptable because the locking logic differs significantly (single price*qty vs stepwise sum).

## D2: Dedicated RabbitMQ Exchange for Auction Events

**Decision**: Use a separate `auction.exchange` (TopicExchange) for all auction events rather than reusing `order.exchange`.

**Reason**: (1) Clean separation of concerns - auction events have different semantics. (2) Avoids routing key collision. (3) Easier to monitor and debug auction-specific traffic. (4) Each module only subscribes to auction queues if it participates in the auction flow.

## D3: Bid Collection in MatchEngine Redis, Persistence in Order DB

**Decision**: Bids are first collected in matchEngine Redis (for fast atomic operations and clearing), then separately persisted to eap-order PostgreSQL (for query, audit, history).

**Reason**: The clearing algorithm needs all bids in-memory for computation. Redis Lua scripts provide atomic bid collection with gate closure check. PostgreSQL provides durable storage for querying results after clearing. The flow is: eap-order (entry) -> matchEngine REST (Redis collect) + RabbitMQ event -> wallet (lock) + order DB (persist).

**Trade-off**: Brief window where bid exists in Redis but not yet in DB. Acceptable because Redis is the source of truth for clearing, and DB is for query/audit.

## D4: Clearing Algorithm as Pure Service (No Side Effects)

**Decision**: AuctionClearingService.clear() is a pure function that takes bid lists as input and returns a ClearingResult. No Redis/RabbitMQ calls inside.

**Reason**: Makes the clearing algorithm highly testable. The orchestration (read from Redis -> clear -> publish event) is handled by AuctionSchedulerService.

## D5: Stepwise Bid Expansion for Clearing

**Decision**: The clearing algorithm expands stepwise bids into flat (price, quantity) pairs, then builds aggregate demand/supply curves. This is mathematically equivalent to treating each step as an independent bid at that price level.

**Reason**: Standard approach in power market clearing. Each step represents a willingness to buy/sell a specific quantity at a specific price. Aggregation across all participants gives market-wide supply/demand curves.

## D6: AuctionBidSubmittedEvent Carries Pre-calculated totalLocked

**Decision**: The AuctionBidSubmittedEvent carries a pre-calculated `totalLocked` field (computed by eap-order at submission time) rather than having wallet recalculate from steps.

**Reason**: (1) Single point of calculation reduces inconsistency risk. (2) Wallet doesn't need to understand stepwise bid semantics - it just locks the amount. (3) Consistent with existing pattern where OrderSubmittedEvent carries price*amount.

## D7: AuctionClearedEvent.BidResult Carries originalTotalLocked

**Decision**: Each per-user BidResult in AuctionClearedEvent includes `originalTotalLocked` so wallet can correctly calculate refunds without needing to look up the original bid.

**Reason**: Wallet settlement needs to know how much was originally locked to compute: refund = originalTotalLocked - settlementAmount (for buyers). Without this, wallet would need to query the bid or maintain its own record of locked amounts per auction.

## D8: Scheduled Clearing with Distributed Lock

**Decision**: Use Spring @Scheduled with Redisson distributed lock for auction lifecycle management (:00 open, :10 close+clear).

**Reason**: (1) Simple and reliable for hourly scheduling. (2) Distributed lock ensures only one instance triggers clearing in multi-instance deployment. (3) No need for a dedicated scheduler service.

**Trade-off**: @Scheduled cron is wall-clock based. If the application restarts at :09, it won't retroactively open the missed :00 auction. Acceptable for MVP.

## D9: Pro-rata Marginal Allocation - Integer Rounding with Remainder Distribution

**Decision**: For marginal order pro-rata allocation, allocate floor(proportion * available) to each, then distribute remainder one unit at a time by submission order.

**Reason**: Energy amounts are integers. Simple floor + remainder distribution ensures total allocated equals total available without complex rounding logic.

## D10: Price Limits as Admin-Configurable Redis Config

**Decision**: Store price floor/ceiling in Redis (`auction:config` hash) rather than application.yml.

**Reason**: Allows runtime changes without restart. Admin can update via PUT /v1/auction/config. application.yml provides initial defaults.
