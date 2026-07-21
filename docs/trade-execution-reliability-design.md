# TradeExecuted 成交事實與跨服務對齊設計

> 建立日期：2026-06-30  
> 目的：定義 MatchEngine 媒合後的成交事實來源、Order / Wallet 如何在非同步架構下對齊成交結果，以及後續實作工項。

> Current status: this design has been superseded by the `TradeExecuted -> OrderTradeApplied / WalletTradeSettled -> completion markers` runtime flow. The legacy `OrderMatchedEvent` runtime bus and matched queues were retired in TPS-80; references below describe the migration history, not the current architecture.

## 背景

EAP 目前已完成：

- Wallet-first validation：訂單進入撮合前，Wallet 先完成驗資與資產鎖定。
- Order Event Sourcing：Order submit / wallet confirmed / wallet failed / matched 等生命週期事件已切入 `order_event_store`。
- Redis + Lua Matching Engine：撮合核心可維持 price-time priority、partial fill 與 quantity conservation。
- Outbox / DLQ / retry / idempotent consumer：已經建立基本 MQ reliability。

近期 MatchEngine 壓測結果顯示：

- Redis/Lua 撮合核心約 `3,700 ~ 5,800 matches/s`。
- 加入 RabbitMQ listener / publish 後，MatchEngine AMQP baseline 約 `1,000 matches/s`。
- Order matched DB hot path 約 `460 matches/s`，因為一筆 match 至少造成：
  - `match_history` 一筆寫入
  - buyer order stream append `OrderMatchedV1`
  - seller order stream append `OrderMatchedV1`

這代表 MatchEngine 本身不是目前瓶頸；真正的壓力在「成交後的 downstream DB 寫入」。

但 `matched` 不是單純查詢 projection。成交後，使用者合理期待：

- 訂單狀態反映成交。
- Wallet 餘額 / 鎖定金額完成 settlement。
- 系統可以證明成交事實確實存在，且 Order / Wallet 最終都有對齊。

因此不能把 matched hot path 全部降級成慢慢更新的 projection。需要重新定義：

1. 什麼是成交事實。
2. 哪些寫入是業務完成必要狀態。
3. 哪些寫入只是查詢 / 通知 / 歷史 view，可延後處理。

## 核心結論

### 1. `TradeExecuted` 應成為獨立成交事實

Order Event Store 不應承擔所有成交事實。成交本身應從 Order lifecycle 中獨立出來，建立另一條 event source：

```text
TradeExecuted
  tradeId
  marketId
  sequence
  buyOrderId
  sellOrderId
  price
  quantity
  occurredAt
```

原因：

- 一筆成交天然連結買單與賣單，不屬於單一 order aggregate。
- 若硬把成交拆成 buyer / seller 兩條 order event stream，會造成寫入放大。
- 面試與系統設計上，獨立 execution / trade ledger 更容易說明「成交事實 source of truth」。

### 2. MatchEngine 可作為 `TradeExecuted` 的單一寫入者

目前不先拆 Trade Service。

設計理由：

- MatchEngine 是唯一知道 Redis order book 實際撮合結果的元件。
- 撮合後立刻持久化 `TradeExecuted`，可以避免「只靠 MQ event 表示成交」導致事實遺失。
- 另拆 Trade Service 雖然邊界漂亮，但會新增 MatchEngine -> Trade Service 之間的可靠性問題；目前專案階段不值得先引入。

因此短中期採用：

```text
MatchEngine
  Redis Lua matching
  -> append trade_executions
  -> append trade_outbox
  -> publish TradeExecuted
```

### 3. Trade DB 不可變成共享資料庫

`trade_executions` 是 MatchEngine / Trade domain 的私有資料，不是 Order / Wallet 的同步查詢中心。

禁止設計：

```text
Order  -> query/update trade_executions
Wallet -> query/update trade_executions
```

否則只是把熱點從 Order DB 移到 Match DB，並造成跨服務共享資料庫耦合。

正確互動方式：

```text
MatchEngine
  -> publish TradeExecuted

Order Service
  -> consume TradeExecuted
  -> update local order execution state

Wallet Service
  -> consume TradeExecuted
  -> perform local settlement ledger update
```

## 可靠性模型

這個架構不追求 Order / Wallet 在同一瞬間強一致，而是保證：

> `TradeExecuted` 是唯一成交事實，Order / Wallet 必須最終收斂到這個事實；若沒有收斂，系統要能偵測、重送與修復。

重點不是避免短暫差異，而是避免永久差異。

## MatchEngine Transactional Outbox

MatchEngine 不能只做：

```text
insert trade_executions
publish TradeExecuted
```

這會有兩種失敗：

- DB 寫入成功，MQ publish 失敗。
- MQ publish 成功，DB 寫入失敗。

必須改成同一個 DB transaction：

```text
BEGIN;

insert into trade_executions (
  trade_id,
  market_id,
  sequence,
  buy_order_id,
  sell_order_id,
  price,
  quantity,
  occurred_at
);

insert into trade_outbox (
  event_id,
  aggregate_type,
  aggregate_id,
  event_type,
  payload,
  status,
  created_at
);

COMMIT;
```

然後由 outbox relay 非同步 publish `TradeExecuted`。

這保證：

```text
只要成交事實進 DB，就一定有一筆 outbox event 等待發布。
```

MQ 故障時，事件不會遺失，只會停留在 pending。

## Order / Wallet Idempotent Consumer

Order / Wallet 不能只相信「收到 MQ 訊息」就代表處理成功。每個 consumer 都必須以 `tradeId` 做冪等。

### Order Service

建議新增：

```text
order_execution_links
  trade_id       unique
  order_id
  side
  price
  quantity
  applied_at
```

消費流程：

```text
receive TradeExecuted
begin transaction

if trade_id already exists in order_execution_links:
  ack
  return

update order filled quantity / status
insert order_execution_links for buyer
insert order_execution_links for seller
optionally append OrderTradeApplied outbox

commit
ack
```

### Wallet Service

建議新增或確認：

```text
wallet_settlements
  trade_id       unique
  buy_order_id
  sell_order_id
  status
  settled_at
```

以及 ledger：

```text
wallet_ledger
  ledger_id
  wallet_id
  trade_id
  entry_type
  amount
  created_at
```

消費流程：

```text
receive TradeExecuted
begin transaction

if trade_id already exists in wallet_settlements:
  ack
  return

debit buyer locked amount
credit seller balance
insert wallet ledger entries
insert wallet_settlements
optionally append WalletTradeSettled outbox

commit
ack
```

如果 DB commit 前失敗，MQ 不 ack，等待重送。  
如果 DB commit 成功但 ack 失敗，MQ 會重送，但 unique constraint 會讓第二次處理變成 no-op。

這不是 MQ exactly-once，而是：

```text
at-least-once delivery
+ idempotent consumer
+ DB unique constraint
= effectively-once business effect
```

## 成交完成狀態

建議將交易狀態拆開：

```text
TRADE_EXECUTED
ORDER_APPLIED
WALLET_SETTLED
TRADE_COMPLETED
```

使用者查詢時也應接受中間狀態：

```text
MATCHED_SETTLEMENT_PENDING
SETTLED
FAILED_NEEDS_REPAIR
```

因為 Wallet 已在撮合前完成 reservation，settlement 正常情況應成功。若 Wallet settlement 失敗，通常代表：

- DB timeout
- consumer crash
- duplicate event
- transient infra error
- lock conflict

這些應 retry。

如果出現：

- locked amount 不存在
- locked amount 不足
- orderId 不存在

這代表系統 invariant 壞掉，應進 DLQ / alert / manual repair，不應自動取消 `TradeExecuted`。

## Completion Projection

可以新增一個 completion projection，用於查詢與監控：

```text
trade_completion_view
  trade_id
  trade_executed_at
  order_applied_at
  wallet_settled_at
  status
  last_error
```

來源事件：

```text
TradeExecuted
OrderTradeApplied
WalletTradeSettled
```

當三者都完成：

```text
status = TRADE_COMPLETED
```

若超過門檻時間未完成：

```text
status = TRADE_SETTLEMENT_DELAYED
```

這張表不是核心交易事實，而是監控 / 查詢 read model。

## Reconciliation

不能只相信 MQ。需要週期性 reconciliation。

目標：

```text
掃描 trade_executions
找出沒有 order_execution_links 的 trade_id
找出沒有 wallet_settlements 的 trade_id
重新投遞 TradeExecuted 或放入 repair queue
```

這個流程可以由 MatchEngine / repair worker 提供：

```text
GET /internal/trades?marketId=ENERGY-SPOT&fromSequence=xxx&limit=1000
```

或直接由內部 repair job 讀 trade DB 後重新 publish missing events。

注意：這是可靠性補償流程，不是每筆交易 hot path 的同步查詢。

## 效能設計原則

### 1. `trade_executions` append-only

不要讓 Wallet settlement 回寫：

```text
update trade_executions set settlement_status = SETTLED
```

這會讓 trade table 從 append-only 變成熱 update table。

Wallet 是否 settlement 應存在 Wallet 自己的：

```text
wallet_settlements
wallet_ledger
```

或由 completion projection 合併。

### 2. 依 market 分區 / 分序列

不要所有市場共用一條 global sequence。

```text
unique(market_id, sequence)
unique(trade_id)
```

未來可依 `marketId` 分 shard 或 partition。

### 3. 查詢流量走 projection

`trade_executions` 不直接承擔高頻使用者查詢。

查詢模型應由 event 建立：

```text
user_trade_history
market_recent_trades
order_execution_view
trade_completion_view
```

## 目標架構

```text
MatchEngine
  Redis Lua matching
  -> DB transaction:
       insert trade_executions
       insert trade_outbox
  -> outbox relay publish TradeExecuted

Order Service
  consume TradeExecuted
  -> transaction:
       unique(trade_id)
       update order filled quantity/status
       insert order_execution_links
       insert order_outbox OrderTradeApplied
  -> ack after commit

Wallet Service
  consume TradeExecuted
  -> transaction:
       unique(trade_id)
       debit buyer locked balance
       credit seller balance
       insert wallet_ledger
       insert wallet_settlements
       insert wallet_outbox WalletTradeSettled
  -> ack after commit

Trade Completion Projection
  consume TradeExecuted / OrderTradeApplied / WalletTradeSettled
  -> build trade_completion_view

Reconciliation
  periodically compare TradeExecuted sequence with Order/Wallet processed state
  -> republish missing events / alert DLQ
```

## 實作工項拆分

### 2026-06-30 Phase 1 / Phase 2 實作快照

已完成第一段落地：

- `eap-common` 新增 `TradeExecutedEvent`。
- RabbitMQ constants 新增 `TRADE_EXCHANGE` / `TRADE_EXECUTED_KEY`。
- `eap-matchEngine` 新增 PostgreSQL / Liquibase / JPA 支援。
- MatchEngine 新增 `match_engine.trade_executions`：
  - `trade_id` unique
  - `market_id + sequence` unique
  - `legacy_match_id` unique
  - buyer / seller order、price、quantity、market sequence、occurredAt
- MatchEngine 新增 `match_engine.trade_outbox`：
  - `event_type`
  - `aggregate_type`
  - `aggregate_id`
  - `routing_key`
  - `payload`
  - `status / attempt_count / retry metadata`
- `MatchingEngineService` 在 Redis/Lua 撮合成功後，先透過 `TradeExecutionRecorder` 寫入 `trade_executions + trade_outbox`。TPS-80 後不再 publish legacy `OrderMatchedEvent`。

驗證：

```text
eap-common publishToMavenLocal: PASS
eap-matchEngine test: PASS
matchEngineCoreLoadTest --events 100 --workers 8: PASS
DB schema check: match_engine.trade_executions / trade_outbox exist
```

尚未完成：

- MatchEngine `trade_outbox` relay 已在後續 Phase 3 補上。
- Order 已在後續 Phase 4 補上 `TradeExecuted` consumer。
- Wallet 尚未 consume `TradeExecuted`。
- 舊 `OrderMatchedEvent` 流程已在 TPS-80 退役；現行流程以 `TradeExecutedEvent` 為成交事實。

### 2026-06-30 Phase 3 / Phase 4 實作快照

Phase 3 已完成：

- MatchEngine 新增 `TradeOutboxRelay`。
- Relay 會掃 `match_engine.trade_outbox`，publish `TradeExecutedEvent` 到 `trade.exchange / trade.executed`。
- 支援 publisher confirm、retry、FAILED 狀態與 metrics：
  - `trade_outbox_published_total`
  - `trade_outbox_publish_failed_total`
  - `trade_outbox_retry_scheduled_total`
  - `trade_outbox_publish_duration`
  - `trade_outbox_pending`
  - `trade_outbox_failed`
  - `trade_outbox_oldest_pending_age_seconds`

Phase 4 已完成 Order 第一段落地：

- `eap-common` 新增 `ORDER_TRADE_EXECUTED_QUEUE = order.tradeExecuted.queue`。
- `eap-order` 新增 `order_service.order_execution_links`，以 unique `(trade_id, order_id)` 做本地冪等。
- `eap-order` 新增 `TradeExecutedListener`，綁定 `trade.exchange / trade.executed`。
- Listener 收到 `TradeExecutedEvent` 後：
  - 對 buyer order append `OrderMatchedV1`
  - 對 seller order append `OrderMatchedV1`
  - 寫入 `order_execution_links` 作為 tradeId 對齊與冪等紀錄
- 舊 `OrderMatchedEvent` listener 已在 TPS-80 退役；`match_history` / WebSocket / market data 不再由 legacy matched bus 同步推進。

驗證：

```text
eap-matchEngine test: PASS
TradeOutboxRelayTest: PASS
eap-common publishToMavenLocal: PASS
eap-order test: PASS
```

目前邊界：

- Order 已能 consume `TradeExecuted` 並對齊本地 Order Event Store。
- Wallet 尚未 consume `TradeExecuted`，settlement ledger 還沒切換。
- `OrderTradeApplied` outbox event 尚未實作；目前 Phase 4 只先落地本地 order state alignment。
- 舊 `OrderMatchedEvent` 與新 `TradeExecutedEvent` 的短期並行期已結束；現行 Order 成交套用只支援 `TradeExecutedEvent`。

### Phase 1：設計落地與 schema 準備

目標：先建立成交事實模型，不改動完整 E2E 行為。

- [x] 定義 `TradeExecuted` event schema。
- [x] 在 MatchEngine 新增 `trade_executions` schema。
- [x] 在 MatchEngine 新增 `trade_outbox` schema。
- [x] 定義 `tradeId` 產生規則。
- [x] 定義 `marketId + sequence` unique constraint。
- [x] 文件化 `TradeExecuted` 與舊 `OrderMatchedEvent` 的關係：TPS-80 後 legacy event bus retired。

驗收：

- MatchEngine 可以在測試中產生 deterministic `TradeExecuted` payload。
- schema 不破壞既有 matching tests。

### Phase 2：MatchEngine 持久化成交事實

目標：撮合成功後，將成交事實 append 到 DB。

- [x] 將 Redis Lua matching result 轉成 `TradeExecuted`。
- [x] 同 transaction 寫入 `trade_executions` 與 `trade_outbox`。
- [ ] 補 duplicate `tradeId` / duplicate `marketId + sequence` 測試。
- [ ] 補 DB commit fail / outbox pending 測試。

驗收：

- 每筆 Redis match 都有對應 `trade_executions`。
- DB 寫入失敗時不 publish event。
- DB 成功但 MQ relay 停止時，`trade_outbox` 保留 pending event。

### Phase 3：MatchEngine Outbox Relay

目標：可靠發布 `TradeExecuted`。

- [x] 建立 MatchEngine outbox relay。
- [x] 支援 publisher confirm。
- [x] 支援 retry / FAILED / oldest pending age metrics。
- [x] 新增 `trade_outbox_pending`、`trade_outbox_published_total`、`trade_outbox_publish_failed_total` metrics。

驗收：

- MQ 停止時 pending 增加，不丟事件。
- MQ 恢復後 pending drain 到 0。
- duplicate publish 不造成 downstream 重複業務效果。

### Phase 4：Order consume `TradeExecuted`

目標：Order 依成交事實更新本地狀態，但不讀 MatchEngine DB。

- [x] 新增 `order_execution_links`。
- [x] 新增 TradeExecuted listener。
- [x] 以 `tradeId` unique constraint 做冪等。
- [x] 更新 buyer / seller filled quantity 與 order status。
- [ ] 發布 `OrderTradeApplied` outbox event。
- [x] 淘汰舊 `OrderMatchedEvent` consumer hot path：TPS-80 completed。

驗收：

- 同一 `TradeExecuted` 重送 N 次，Order 狀態只更新一次。
- buyer / seller filled quantity 正確。
- partial fill / full fill 狀態正確。

### Phase 5：Wallet consume `TradeExecuted`

目標：Wallet settlement 以 `TradeExecuted` 為唯一成交輸入。

- [x] 新增 `wallet_service.trade_settlements`，以 `trade_id` 作為 settlement idempotency key。
- [x] 確認 settlement entry 先以本地 processed marker + outbox 形式落地；完整 wallet ledger 可作為後續增強。
- [x] 新增 TradeExecuted listener。
- [x] 以 `tradeId` unique constraint 做 settlement 冪等。
- [x] 成功後發布 `WalletTradeSettled` outbox event。
- [x] 不回寫 `trade_executions`。

驗收：

- 同一 `TradeExecuted` 重送 N 次，Wallet debit / credit 只發生一次。
- buyer locked amount 正確釋放 / 扣除。
- seller balance 正確增加。
- 不存在 locked amount 時進 DLQ / repair，不自動取消成交。

### Phase 6：Completion Projection 與 reconciliation

目標：偵測 Order / Wallet 是否對齊 `TradeExecuted`，避免永久差異。

- [x] 新增 `trade_completion_view`。
- [x] 消費 `OrderTradeApplied` / `WalletTradeSettled`，並由 MatchEngine 在寫入 `TradeExecuted` 時標記成交已發生。
- [x] 標記 `TRADE_EXECUTED`、buyer/seller `ORDER_APPLIED`、`WALLET_SETTLED`、`TRADE_COMPLETED`。
- [x] 增加 delayed threshold，預設 30 秒未完成視為 delayed。
- [x] 新增 reconciliation job：回補缺失的 completion row、補上已 ready 但未完成的 row、掃描 incomplete delayed trades。
- [x] 支援 republish / repair / alert：reconciliation 會將對應 `TradeExecutedEvent` outbox row 重排為 `PENDING`，並以 log + metrics 暴露 delayed 狀態。

驗收：

- 任一 downstream consumer 暫停後恢復，completion 最終回到 completed。
- 人為刪除 / 漏處理 processed marker 時，reconciliation 能偵測。
- delayed trade 可被查詢與告警。

### Phase 7：壓測與驗證

目標：確認新架構是否降低 matched hot path 寫入放大，並保持可靠性。

- [ ] MatchEngine `TradeExecuted` DB append baseline。
- [ ] MatchEngine outbox publish baseline。
- [x] Order `TradeExecuted` consumer idempotency test。
- [x] Wallet `TradeExecuted` settlement idempotency test。
- [ ] Full E2E：`MatchEngine -> TradeExecuted -> Order/Wallet -> completion`。

觀察指標：

```text
trade_executions append TPS
trade_outbox pending / oldest pending age
Order TradeExecuted consumer TPS
Wallet settlement TPS
trade_completion delayed count
DLQ count
duplicate tradeId count
Order / Wallet missing processed count
```

驗收方向：

- MatchEngine 不因 Order / Wallet DB 壓力被同步阻塞。
- Order / Wallet 可短暫落後，但能 drain。
- `TradeExecuted` 沒有遺失。
- duplicate event 不造成重複 settlement。
- completion projection 可證明最終收斂。

## 面試說法

可以這樣說：

> 我沒有使用分散式 transaction。成交事實由 MatchEngine append-only persist，並透過 transactional outbox 發布 `TradeExecuted`。Order 和 Wallet 以 at-least-once event delivery 消費，靠 inbox / unique constraint / idempotent transaction 確保業務效果只執行一次。跨服務一致性不是同步強一致，而是由 completion projection、retry、DLQ 與 reconciliation 保證最終收斂。

避免這樣說：

> MQ 保證 Order / Wallet 一定同時成功。

更精準的說法是：

> MQ 只保證 at-least-once delivery；真正的可靠性來自 transactional outbox、idempotent consumer、唯一鍵、retry、DLQ 與 reconciliation。
