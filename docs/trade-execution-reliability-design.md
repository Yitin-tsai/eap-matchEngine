# TradeExecuted 成交事實與可靠性設計

> 現行規範更新：2026-08-07。本文描述 CDA 成交路徑目前程式碼的責任與故障模型；舊 `OrderMatchedEvent` 及 MatchEngine completion feedback loop 已退役。TDA 使用不同事件流，其直接發布缺口與容量邊界請見 [EAP architecture](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/architecture.md)，不得沿用本文的 outbox 與 TPS 證據。

## 核心決策

`TradeExecutedEvent` 是一筆成交的權威事實。MatchEngine 是唯一寫入者，因為它是唯一知道 Redis order book 實際撮合結果的服務。

```text
MatchEngine
  -> Redis Lua 原子撮合
  -> PostgreSQL trade_executions
  -> PostgreSQL trade_outbox
  -> RabbitMQ trade.exchange / trade.executed
       -> Order durable inbox + command-side trade application
       -> Wallet explicit-transaction settlement
```

Order 與 Wallet 不查詢或更新 MatchEngine 的交易資料庫。每個服務只提交自己的資料，系統不使用跨服務分散式交易。

## 服務責任

| 元件 | 權威資料 | 不負責 |
| --- | --- | --- |
| MatchEngine | Redis order book、撮合決策、`trade_executions`、trade outbox | Order 狀態、Wallet 結算、下游完成狀態 |
| Order | 命令端 order event stream、`order_trade_applications`、durable trade inbox | 成交價格決策、資產異動 |
| Wallet | 餘額、保留額、`trade_settlements` | 撮合或 Order 投影 |

MatchEngine 不接收 `OrderTradeAppliedEvent` 或 `WalletTradeSettledEvent`，也不保存 `trade_completion_view`。營運與壓測工具在交易路徑外核對三個服務的 durable facts。

## 撮合與資料庫交易邊界

`OrderConfirmedListener` 將 Wallet 已確認的訂單送入 `MatchingEngineService`。撮合流程保留以下順序：

1. Redis Lua 原子取得或保留可成交的 resting order。
2. 建立包含買賣方 order/user、市場順序、價格與數量的 `TradeExecutedEvent`。
3. 在同一個 MatchEngine 資料庫交易中寫入：
   - `trade_executions`；
   - `trade_outbox`；
   - 需要延後清除時的 `reservation_cleanup_tasks`。
4. 資料庫提交成功後，交易事實不因後續 RabbitMQ 或 Redis 清理暫時失敗而消失。

`trade_id` 是 `trade_executions` 主鍵。重複處理同一筆成交時，資料庫唯一性避免建立第二份交易與 outbox。

## Trade Outbox

`TradeOutboxRelay` 定期讀取到期的 `PENDING` rows，發布 persistent `TradeExecutedEvent`，等待 broker confirmation 後標記 `SENT`。失敗會更新 attempt、backoff、last error；達上限後進入可觀測的 `FAILED` 狀態。

發布成功但標記 `SENT` 前當機，可能造成再次發布。這是 transactional outbox 的正常 at-least-once 故障模式，下游必須冪等。

Relay 可使用有限的 publisher executor 並行發布，但 executor 只有在排程方法進入後才會收到工作。排程本身的隔離仍是獨立容量問題。

## 延後清除 Redis Reservation

成交 hot path 不同步完成所有 resting-order 清理。需要清理時，資料庫交易會建立 `reservation_cleanup_tasks`，由 `ReservationCleanupWorker` 後續處理：

- 以有限 batch 取得 `PENDING` 或逾時的 `PROCESSING` task；
- 以 lease renewal 保護長批次；
- 呼叫 Redis order-book cleanup；
- 批次標記完成；
- 失敗使用持久化 retry/backoff，達上限後保留失敗狀態。

`ReservationReconciler` 另外掃描 Redis 中超過門檻的 reservation：存在 durable trade 時完成或釋放剩餘量；不存在 durable trade 時釋放 orphan reservation。它是當機修復機制，不是下游 Order/Wallet completion reconciler。

## 下游冪等與 ACK

### Order

Order 在手動 ACK 前先保存 `TradeExecutedEvent` 至 durable inbox，再以 `trade_id` claim 套用買賣雙方命令端事件。若 command state 尚未可套用，事件留在 inbox，由本地 reconciler 重試；不要求 Order query projection 先完成。

### Wallet

Wallet 在每個事件的一個明確資料庫交易內，以固定 UUID 順序鎖定買賣雙方 Wallet、更新資產並插入 `trade_settlements`。`trade_id` 主鍵使重送成為 no-op。任何 postcondition 失敗都必須能回復整個交易，因此 statement autocommit 版本已被拒絕。

## 完整業務交易關卡

單一 `TradeExecuted` row、HTTP 200 或 queue 最後清空都不足以證明完成。完整驗證要求：

1. MatchEngine、Order、Wallet 具有完全相同的 `trade_id` 集合。
2. 買賣雙方資產與 locked amount 正確。
3. 預期完全成交時，Redis BUY/SELL order book 與 active reservations 歸零。
4. 量測範圍內的 RabbitMQ ready/unacked、DLQ 與各服務 durable retry debt 清空。
5. 重複、訂單重用與不變條件檢查通過。

Order query projection 是可重建 read model。它的延遲應監測，但不建立 MatchEngine 擁有的第 4 份完成狀態。

## 故障模型

| 故障點 | 預期結果 |
| --- | --- |
| Redis reserve 後、durable trade 前當機 | reservation reconciler 釋放 orphan，訂單量守恆 |
| durable trade 提交後、Redis cleanup 前當機 | cleanup task/reconciler 完成清理，不產生重複交易 |
| broker confirm 後、outbox 標記前當機 | 可能重送，下游以 `trade_id` 冪等吸收 |
| Order/Wallet commit 前 consumer 當機 | 不 ACK，RabbitMQ 重送 |
| Order/Wallet commit 後 ACK 前當機 | 重送但不重複套用或結算 |
| 不可恢復訊息或 invariant 破壞 | DLQ/FAILED debt、告警與人工修復，不自動取消 durable trade |

## 監測

- MatchEngine: trade insert、outbox select/publish/confirm/mark-sent、reservation cleanup/reconcile timers。
- RabbitMQ: 每條 queue 的 ready、unacked、redelivery 與 DLQ。
- PostgreSQL: pool active/pending、lock/wait、commit、WAL 與 hot SQL。
- Cross-service: Match 到 Order/Wallet durable lag、三服務集合差異、資產差異。

Deep diagnostics 會對單機造成 observer effect，因此只能用來歸因，不能直接取代低觀測容量結果。

## 已知排程風險

截至 2026-08-07，MatchEngine 的 Spring `taskScheduler` 實測只有 1 條執行緒，trade outbox、reservation cleanup、reservation reconciliation 與 auction schedules 共用。deep mixed HTTP 診斷觀察到 reservation cleanup batch 最大 `9.380s`，同時 Match-to-Order 與 Match-to-Wallet p95 約 `7.38s`。

這支持「長 cleanup invocation 阻塞 outbox poll」的假設，但 scheduler isolation 尚未實作或採用。修正必須以同 seed A/B、完整正確性關卡、backlog 與 durable lag 證明，而不能只增加執行緒後看局部 TPS。

## 已退役設計

- legacy `OrderMatchedEvent` bus；
- Order/Wallet 每筆完成回授事件；
- MatchEngine completion marker queues；
- `trade_completion_markers` 與 `trade_completion_view`；
- MatchEngine 根據下游 marker 自動重送交易的 reconciler。

退役原因是下游完成狀態屬於 Order 與 Wallet 的本地責任。將它複製到 MatchEngine 會增加事件、寫入與耦合，卻不能取代各服務自己的 inbox、冪等、retry、DLQ 與外部核對。

## Public Evidence

- [EAP architecture](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/architecture.md)
- [Performance report](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/performance-report.md)
- [2026-08-07 canonical mixed HTTP diagnostic](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/benchmarks/2026-08-07-canonical-mixed-http-diagnostic.md)
