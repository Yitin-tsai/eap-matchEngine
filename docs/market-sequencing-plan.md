# Market Sequencing 與 Audit 重構規劃

> 建立日期：2026-06-03  
> 目的：補上 EAP 在交易公平性與高流量入口上的核心缺口，讓同一市場內的訂單具備可驗證順序，並讓 audit chain 回到單張 order lifecycle 的語意。

> 實作狀態：2026-06-03 已完成 Phase 1、Phase 2，並先以 Redis ZSET composite score 完成 price + sequence 的 MVP 排序。完整 price-level FIFO queue 保留為下一階段強化。

> 2026-08-07 補充：現行成交事件是 `TradeExecutedEvent`，包含買賣雙方 `marketSequence`；`OrderSubmittedEvent` 與 `OrderConfirmedEvent` 也已帶有 `marketId`/`marketSequence`。舊 `OrderMatchedEvent` 已退役。下方「背景限制」與分階段修改描述是 2026-06 的演進紀錄；完整 price-level FIFO、跨節點分片與 Super Stream 仍是未來方向。現行 CDA 容量測試以單一市場、單一撮合權威與 Redis Lua 原子操作為邊界，不能代表 TDA。

## 背景

目前 EAP 已具備：

- Wallet-first validation
- RabbitMQ event-driven flow
- Outbox / DLQ / retry
- Redis Lua atomic matching
- Audit Trail / Event Replay
- Timed Double Auction

當時版本仍有幾個限制（現行 event schema 與 audit 寫入已完成其中一部分）：

- `OrderSubmittedEvent` / `OrderConfirmedEvent` 沒有 `marketId` 與 `marketSequence`
- Redis order book 目前主要以 `price` 排序，同價位 FIFO 不嚴格
- 多個 `eap-order` pod 可以接單，但缺少可驗證的 per-market order acceptance sequence
- audit hash chain 目前是全域串連，語意偏重且會讓 audit 寫入序列化

## 核心設計

將「市場順序」與「訂單生命週期完整性」拆開：

```text
marketId + marketSequence
  -> 證明同一市場內訂單被系統接受的順序

per-order audit hash chain
  -> 證明單張訂單生命週期沒有被竄改
```

### Ordering Domain

每個 `marketId` 是一個獨立排序域。只會互相撮合的訂單，才共享同一條 sequence。

範例：

```text
ENERGY-SPOT
AUCTION:ENERGY:2026-06-03T15:00
BTC-USD-SPOT
ETH-USD-SPOT
```

不同 market 之間不需要共享順序，可平行擴充。

## Market Sequence 產生方式

MVP 使用 Redis atomic counter：

```text
INCR seq:{marketId}
```

例如：

```text
INCR seq:ENERGY-SPOT -> 101
INCR seq:ENERGY-SPOT -> 102
INCR seq:ENERGY-SPOT -> 103
```

注意：不可使用 `GET -> +1 -> SET`，必須使用 Redis 原子命令。

多個 `eap-order` pod 可以同時接單，只要都使用同一個 Redis `INCR seq:{marketId}`，sequence 仍合法：

```text
pod-1 -> seq 101
pod-3 -> seq 102
pod-2 -> seq 103
```

這代表順序來源不是單一 order pod，而是共享 atomic sequencer。

## Event Schema 變更

### OrderSubmittedEvent

新增：

```java
private String marketId;
private Long marketSequence;
```

### OrderConfirmedEvent

新增：

```java
private String marketId;
private Long marketSequence;
```

Wallet listener 必須原樣 pass-through，不可重新產生 sequence。

### TradeExecutedEvent

TPS-80 後 legacy `OrderMatchedEvent` 已退役。成交事實由 `TradeExecutedEvent` 表示，需保留買賣雙方 order sequence：

```java
private String marketId;
private Long buyerMarketSequence;
private Long sellerMarketSequence;
```

這讓 match result 可回推成交涉及的兩筆 order 在市場中的位置。

## Order Service 變更

### PlaceBuyOrderService / PlaceSellOrderService

下單流程改成：

```text
1. 決定 marketId
2. Redis INCR seq:{marketId}
3. 建立 OrderSubmittedEvent(orderId, userId, marketId, marketSequence, price, amount, side)
4. 原子寫入 `OrderSubmissionRequestedV1` 與 `OrderSubmittedEvent` integration outbox
5. 非同步 relay 發布 `OrderSubmittedEvent`
6. 回傳 orderId + marketId + marketSequence
```

若目前尚未支援多市場，可先寫死：

```text
marketId = "ENERGY-SPOT"
```

未來再從 request 或產品設定取得。

## Order Book 變更

### 目前問題

原本 Redis ZSET score 主要使用 price。同價位時，Redis 會依 member 排序，不等於交易系統接受順序。

2026-06-03 已先改為 composite score：

```text
BUY  score = price * SCORE_FACTOR + (SCORE_FACTOR - marketSequence)
SELL score = price * SCORE_FACTOR + marketSequence
```

目前效果：

- BUY 仍可用最高 score 取得最佳買價，且同價位 sequence 較小者先被取出
- SELL 仍可用最低 score 取得最佳賣價，且同價位 sequence 較小者先被取出
- Redis key 已加入 `marketId`，不同 market 的 order book 分開

此做法是低改動 MVP。限制是 `marketSequence` 需要被 `SCORE_FACTOR` 安全包住；超高交易量或長期 production 應改成下方 price level + FIFO queue。

### 目標模型

完整版本建議改成 price level + FIFO queue：

```text
orderbook:{marketId}:buy:prices          ZSET
orderbook:{marketId}:sell:prices         ZSET
orderbook:{marketId}:buy:{price}:queue   LIST
orderbook:{marketId}:sell:{price}:queue  LIST
order:{orderId}                          JSON
```

排序語意：

```text
BUY:  price desc, marketSequence asc
SELL: price asc,  marketSequence asc
```

處理方式：

- price ZSET 找最佳價格層
- price-level LIST 用 `LPOP` 取最早進入該價格層的 order
- queue 空時移除該 price level

這才是嚴格 price-time priority。

## Audit 重構

### 目前狀況

目前 `audit_events` 的 `prevHash` 來自整張表最新一筆 event，因此是全域 hash chain。

這能證明全系統事件順序未被竄改，但缺點是：

- audit 寫入被全域序列化
- order / auction / system event 混在同一條 chain
- 對「單張 order lifecycle」而言語意過重

### 目標模型

改為 per-order / per-correlation hash chain：

```text
Order A:
  ORDER_SUBMITTED -> ORDER_CONFIRMED -> ORDER_MATCHED

Order B:
  ORDER_SUBMITTED -> ORDER_FAILED
```

查詢上一筆 hash 時應以 `correlationId` 限定：

```java
findLatestByCorrelationIdForUpdate(correlationId)
```

而不是全域：

```java
findLatestForUpdate()
```

### 為什麼 marketSequence 足夠承擔市場順序

市場公平性不需要靠全域 audit chain 證明。`marketId + marketSequence` 已經是交易系統承認的 ordering source。

Audit payload 必須記錄：

```text
orderId
marketId
marketSequence
eventType
payload
prevHash
hash
createdAt
```

這樣可以：

- 用 `correlationId = orderId` 查單張 order lifecycle
- 用 `marketId + marketSequence` 查市場順序
- 用 per-order hash chain 證明單張 order 歷程未被竄改

## Hot Path 與高流量保護

### 這個方案能解決

| 問題 | 是否改善 | 說明 |
|------|----------|------|
| 多個 order pod 是否能產生合法順序 | 能 | Redis `INCR seq:{marketId}` 是共享 atomic sequencer |
| 同市場訂單順序是否可驗證 | 能 | `marketSequence` 是唯一排序來源 |
| 同價位 FIFO 是否可實作 | 能 | order book 改 price level + FIFO queue |
| audit 是否不再全域互卡 | 能 | 改 per-order chain 後，不同 order 可平行寫 |
| 面試中的交易公平性說法 | 能 | 可明確說明 price-time priority 的排序來源 |

### 這個方案不能單獨解決

| 問題 | 說明 |
|------|------|
| RabbitMQ backlog | 流量超過 consumer 能力時仍會排隊 |
| Wallet DB throughput | 每筆訂單仍需要 wallet transaction 與 optimistic lock |
| 單一 match engine 吞吐 | 同一 market 的 matching 仍是單 writer / 單 shard 思路 |
| Redis order book Lua 壓力 | 高流量下 Lua / Redis event loop 仍可能是瓶頸 |
| 完整 HFT 等級低延遲 | 仍需 gateway、binary protocol、專用 sequencer、低延遲 event loop 等設計 |

## 建議實作順序

### Phase 1：Sequence 欄位與產生器

- [x] 新增 `MarketSequenceService`
- [x] 使用 Redis `INCR seq:{marketId}`
- [x] `OrderSubmittedEvent` 新增 `marketId` / `marketSequence`
- [x] `OrderConfirmedEvent` pass-through `marketId` / `marketSequence`
- [x] 下單 API response 回傳 `marketId` / `marketSequence`

### Phase 2：Audit 語意修正

- [x] `AuditEventRepository` 新增 `findLatestByCorrelationIdForUpdate(correlationId)`
- [x] `AuditService.record()` 改用 per-correlation prevHash
- [x] audit payload 保留 `marketId` / `marketSequence`
- [x] `OrderReplayService` 讀取 `marketId` / `marketSequence`

### Phase 3：Order Book price-time priority

- [x] Redis key 加入 `marketId`
- [x] MVP：Redis ZSET composite score 使用 `price + marketSequence`
- [ ] 完整版：改為 price level + FIFO queue
- [ ] Lua scripts 重寫：
  - add order
  - get best buy/sell by price level
  - remove order
  - partial fill re-add
- [x] MVP 已確認排序語意設計為同價位以 `marketSequence` FIFO

### Phase 4：入口保護

- per-user rate limit 保留
- 新增 per-market rate limit
- RabbitMQ queue depth 超過門檻時回 429 / 503
- 評估 audit 是否要從同步 hot path 改為 async writer

## 面試說法

可以這樣說：

> EAP 目前已具備 wallet-first、outbox、DLQ、Redis Lua atomic matching 與 audit replay。接近真交易所還缺一個核心 primitive：per-market sequencer。我已先完成 `marketId + marketSequence` 的 MVP：每個 market 自己有一條 sequence，order book 先用 Redis ZSET composite score 表達 price + sequence。這能支援低改動的 price-time priority MVP，但還不是 production-grade exchange ordering。完整版本會演進成 per-market sequencer + price-level FIFO queue。Audit 則從全域 hash chain 調整為 per-order lifecycle chain，市場順序由 `marketSequence` 證明，訂單狀態完整性由 per-order hash chain 證明。這讓 order service 可以多 pod 接單，同時維持市場公平性的可驗證性。

也要誠實補一句：

> 這解決的是交易順序與公平性，不代表單靠這個就能承受無限流量。超大流量仍需要 admission control、queue backlog protection、wallet DB throughput 優化與 matching shard 設計。
