# Integration Test Report: timed-double-auction

- **報告日期**：2026-04-10
- **Phase**：Phase 5 Integration Test
- **測試範圍**：5 modules（eap-common, eap-matchEngine, eap-order, eap-wallet, eap-mcp）
- **前置狀態**：所有 module `review_fix_done`，Code Review major 問題（M-1, M-2, W-1, W-2, O-1, O-2, P-1）已修正
- **最終結果**：**PASS**（原 CONDITIONAL_PASS，條件已滿足）

---

## Alignment Check

比對來源：`impact-analysis.md`（需求項目）vs 各 module 現有程式碼（實作狀態）

### 功能需求對照表

| # | 需求項目 | 實作位置 | 狀態 | 備註 |
|---|----------|----------|------|------|
| F-1 | 每小時 :00 開啟拍賣 | `AuctionSchedulerService.openAuction()` @Scheduled cron `"0 0 * * * *"` | 通過 | 產生 `AUC-{deliveryHour}` 格式 ID |
| F-2 | 每小時 :10 清算 | `AuctionSchedulerService.closeAndClear()` @Scheduled cron `"0 10 * * * *"` | 通過 | |
| F-3 | 密封出價（僅顯示參與人數） | Redis Set `auction:{id}:participants`，`getParticipantCount()` 回傳計數 | 通過 | bid 內容不對外暴露 |
| F-4 | 階梯式出價 (stepwise bid) | `AuctionBidRequest.BidStep`（price, amount）+ `AuctionClearingService.expandBids()` | 通過 | |
| F-5 | 不可修改/刪除出價 | Lua `collect_auction_bid.lua` SISMEMBER 重複檢查，無 update/delete API | 通過 | |
| F-6 | Uniform Price (MCP) 清算 | `AuctionClearingService.clear()` 供需曲線交叉點算法 | 通過 | Code Review 深度驗證正確 |
| F-7 | 成交量最大化 + 中間價規則 | `findMcp()` TreeMap 掃描 max volume，取 `(low+high)/2` | 通過 | |
| F-8 | Pro-rata 邊際分配 | `proRataAllocate()` floor 分配 + 按 insertion order 補餘 | 通過 | |
| F-9 | Admin 可配置價格上下限 | `PUT /v1/auction/config` + `AuctionRedisService.saveGlobalConfig()` | 通過 | 仍無 admin 權限驗證（minor M-3，非 blocker）|
| F-10 | Buyer lock = sum(price×amount) | `PlaceAuctionBidService.submitBid()` + `AuctionBidListener` BUY 分支 | 通過 | wallet-first + outbox 保證 |
| F-11 | Seller lock = sum(amount) | `PlaceAuctionBidService.submitBid()` + `AuctionBidListener` SELL 分支 | 通過 | |
| F-12 | 結算退差（MCP 退買方多鎖） | `AuctionSettlementListener.settleBuyer()` 計算 refund = originalLocked - settlement | 通過 | |
| F-13 | 未成交全額解鎖 | `settleBuyer()` / `settleSeller()` clearedAmount==0 分支 | 通過 | |
| F-14 | DB 持久化（3 tables） | `auction_sessions`, `auction_bids`, `auction_results` + Liquibase changelog | 通過 | |
| F-15 | WebSocket 推送（status / result） | `messagingTemplate.convertAndSend("/topic/auction/status", ...)` 和 `"/topic/auction/result"` | 通過 | |
| F-16 | MCP tool 暴露（3 tools） | `AuctionMcpTool`: `submitAuctionBid`, `getAuctionStatus`, `getAuctionResults` | 通過 | P-1 fix 已補 auctionId auto-fill |
| F-17 | 與 CDA 並存 | auction 使用獨立 `auction.exchange`，CDA 使用 `order.exchange` | 通過 | |

**Alignment Check 結論**：17/17 需求項目均有對應實作，scope 完整，無遺漏。

---

## 跨 Module 契約驗證

### 1. Event Schema 一致性

#### AuctionCreatedEvent

| 欄位 | eap-common 定義 | eap-matchEngine publisher | eap-order consumer |
|------|----------------|--------------------------|-------------------|
| `auctionId` | String | `"AUC-" + deliveryHour.format(...)` | `session.setAuctionId(event.getAuctionId())` |
| `deliveryHour` | LocalDateTime | `now.plusHours(1).withMinute(0)...` | `session.setDeliveryHour(event.getDeliveryHour())` |
| `openTime` | LocalDateTime | `now` | `session.setOpenTime(event.getOpenTime())` |
| `closeTime` | LocalDateTime | `now.plusMinutes(durationMinutes)` | `session.setCloseTime(event.getCloseTime())` |
| `priceFloor` | Integer | `config.getPriceFloor()` | `session.setPriceFloor(event.getPriceFloor())` |
| `priceCeiling` | Integer | `config.getPriceCeiling()` | `session.setPriceCeiling(event.getPriceCeiling())` |
| `createdAt` | LocalDateTime | `now` | `event.getCreatedAt() != null ? ... : LocalDateTime.now()` |

**結論**：一致。

#### AuctionBidSubmittedEvent → AuctionBidConfirmedEvent（wallet-first flow）

| 欄位 | eap-common 定義 | eap-order publisher (Submitted) | eap-wallet (lock + outbox Confirmed) | eap-matchEngine consumer (Confirmed) |
|------|----------------|-------------------------------|--------------------------------------|--------------------------------------|
| `auctionId` | String | `request.getAuctionId()` | pass-through | `event.getAuctionId()` |
| `userId` | UUID | `UUID.fromString(request.getUserId())` | `event.getUserId()` → `findByUserId()` | `event.getUserId()` |
| `side` | String | `request.getSide().toUpperCase()` | `event.getSide()` 判斷 BUY/SELL | `event.getSide().toLowerCase()` |
| `steps` | `List<BidStep>` | mapped from request steps | pass-through to confirmed event | serialized to Redis JSON |
| `totalLocked` | Integer | 計算 sum(price×amount) 或 sum(amount) | `event.getTotalLocked()` 用於鎖定 | 未直接使用（在 Redis JSON 中） |
| `createdAt` | LocalDateTime | `LocalDateTime.now()` | pass-through | 未直接使用 |

**結論**：一致。wallet-first 流程確保只有鎖定資金的 bid 才會到達 matchEngine。

#### AuctionClearedEvent

| 欄位 | eap-common 定義 | eap-matchEngine publisher | eap-order consumer | eap-wallet consumer |
|------|----------------|--------------------------|-------------------|--------------------|
| `auctionId` | String | `auctionId` 變數 | `event.getAuctionId()` | `event.getAuctionId()` (log) |
| `clearingPrice` | Integer | `result.getClearingPrice()` | `session.setClearingPrice(...)` | `event.getClearingPrice()` (log) |
| `clearingVolume` | Integer | `result.getClearingVolume()` | `session.setClearingVolume(...)` | `event.getClearingVolume()` (log) |
| `status` | String | `result.getStatus()` / `"FAILED"` | `session.setStatus(...)` | 未使用 |
| `results` | `List<AuctionBidResult>` | `result.getResults()` mapped | iterated for DB + WebSocket | iterated for settlement |
| `clearedAt` | LocalDateTime | `LocalDateTime.now()` | 未持久化（使用 session.createdAt 代替） | 未使用 |
| `AuctionBidResult.userId` | UUID | `r.getUserId()` | `result.getUserId()` | `result.getUserId()` |
| `AuctionBidResult.side` | String | `r.getSide()` | `result.getSide()` | `result.getSide()` |
| `AuctionBidResult.bidAmount` | Integer | `r.getBidAmount()` | `result.getBidAmount()` | 未使用 |
| `AuctionBidResult.clearedAmount` | Integer | `r.getClearedAmount()` | `result.getClearedAmount()` | `result.getClearedAmount()` |
| `AuctionBidResult.settlementAmount` | Integer | `r.getSettlementAmount()` | `result.getSettlementAmount()` | `result.getSettlementAmount()` |
| `AuctionBidResult.originalTotalLocked` | Integer | `r.getOriginalTotalLocked()` | 未使用（DB 不存） | `result.getOriginalTotalLocked()` — 退差計算關鍵欄位 |

**結論**：一致。`originalTotalLocked` 在 wallet settlement 中正確使用。

**已知遺留問題**（非 blocker）：`AuctionBidResultEvent` 已定義但從未 publish/consume（C-2），建議後續清理。

---

### 2. RabbitMQ Exchange / Routing Key / Queue 綁定驗證

#### Auction Exchange 拓撲

| Exchange | Routing Key | Publisher | Queue | Consumer | 綁定正確 |
|----------|-------------|-----------|-------|----------|---------|
| `auction.exchange` | `auction.created` | matchEngine `AuctionSchedulerService` | `order.auctionCreated.queue` | eap-order `AuctionResultListener.handleAuctionCreated()` | 通過 |
| `auction.exchange` | `auction.bid.submitted` | eap-order `PlaceAuctionBidService` | `wallet.auctionBidSubmitted.queue` | eap-wallet `AuctionBidListener.onAuctionBidSubmitted()` | 通過 |
| `auction.exchange` | `auction.bid.confirmed` | eap-wallet `OutboxPoller` | `matchEngine.auctionBidConfirmed.queue` | eap-matchEngine `AuctionBidConfirmedListener.handleConfirmedBid()` | 通過 |
| `auction.exchange` | `auction.cleared` | matchEngine `AuctionSchedulerService` | `order.auctionCleared.queue` | eap-order `AuctionResultListener.handleAuctionCleared()` | 通過 |
| `auction.exchange` | `auction.cleared` | matchEngine `AuctionSchedulerService` | `wallet.auctionCleared.queue` | eap-wallet `AuctionSettlementListener.handleAuctionCleared()` | 通過 |

**Dead Letter Exchange 設定**：eap-order 宣告 `FanoutExchange("order.dlx")` 並 bind `DEAD_LETTER_QUEUE`；eap-matchEngine 和 eap-wallet 的 queue 設定 `x-dead-letter-exchange=order.dlx`。DLX 由 eap-order 負責宣告，跨 module 共用同一 DLX。

#### 常量來源一致性

所有 module 使用 `eap-common` 的 `RabbitMQConstants` 常量，無任何 module 使用 hardcoded 字串。

驗證結果：
- `AUCTION_EXCHANGE = "auction.exchange"` — 所有 3 個 RabbitMQConfig 使用同一常量 ✓
- `AUCTION_BID_SUBMITTED_KEY = "auction.bid.submitted"` — publisher 和 binding 使用同一常量 ✓
- `AUCTION_BID_CONFIRMED_KEY = "auction.bid.confirmed"` — publisher 和 binding 使用同一常量 ✓
- `AUCTION_CLEARED_KEY = "auction.cleared"` — publisher 和 binding 使用同一常量 ✓
- `AUCTION_CREATED_KEY = "auction.created"` — publisher 和 binding 使用同一常量 ✓
- Queue 名稱常量 — 各 module 的 `@RabbitListener` 和 `QueueBuilder` 使用同一常量 ✓

---

### 3. Feign 介面契約驗證

#### eap-order → eap-matchEngine（EapMatchEngine Feign Client）

| Feign Method | Feign 宣告 | matchEngine Controller | 匹配 |
|---|---|---|---|
| `submitAuctionBid` | `POST /v1/auction/bid` `@RequestBody AuctionBidRequest` | `@PostMapping("bid")` `@RequestBody AuctionBidRequest` | 通過 |
| `getAuctionStatus` | `GET /v1/auction/status` → `AuctionStatusDto` | `@GetMapping("status")` → `AuctionStatusDto` | 通過 |
| `getAuctionConfig` | `GET /v1/auction/config` → `AuctionConfigDto` | `@GetMapping("config")` → `AuctionConfigDto` | 通過 |
| `updateAuctionConfig` | `PUT /v1/auction/config` `@RequestBody AuctionConfigDto` | `@PutMapping("config")` `@RequestBody AuctionConfigDto` | 通過 |

**注意**：`submitAuctionBid` Feign 方法仍存在但已不在主要出價流程中使用（PlaceAuctionBidService 不再呼叫 Feign）。保留供直接 API 呼叫使用。

#### eap-mcp → eap-order（OrderServiceClient Feign Client）

| Feign Method | Feign 宣告 | eap-order McpApiController | 匹配 |
|---|---|---|---|
| `submitAuctionBid` | `POST /mcp/v1/auction/bid` `@RequestBody AuctionBidRequest` | `@PostMapping("/auction/bid")` under `@RequestMapping("/mcp/v1")` = `POST /mcp/v1/auction/bid` | 通過 |
| `getAuctionStatus` | `GET /mcp/v1/auction/status` → `AuctionStatusDto` | `@GetMapping("/auction/status")` = `GET /mcp/v1/auction/status` | 通過 |
| `getAuctionResults` | `GET /mcp/v1/auction/results` `@RequestParam String auctionId` → `AuctionResultDto` | `@GetMapping("/auction/results")` `@RequestParam String auctionId` | 通過 |

**MCP tool auctionId auto-fill（P-1 fix 驗證）**：`AuctionMcpTool.submitAuctionBid()` 先呼叫 `getAuctionStatus()` 取得 `currentStatus.getAuctionId()`，再塞入 `AuctionBidRequest.auctionId`。若無活躍拍賣則提前回傳錯誤訊息。修正正確。

---

### 4. 資料流完整性驗證

完整鏈路（wallet-first + outbox pattern）：

```
[用戶] POST /bid/auction
  → eap-order AuctionController.submitBid()
  → PlaceAuctionBidService.submitBid()
      step 1: request.isValid()（包含 auctionId 非空驗證）
      step 2: 計算 totalLocked
      step 3: AuctionBidRepository.save()（本地 DB，status=SUBMITTED）
      step 4: rabbitTemplate.convertAndSend(auction.exchange, auction.bid.submitted, event)

[eap-wallet] AuctionBidListener.onAuctionBidSubmitted()  @Transactional
  → findByUserId()
  → 餘額檢查（不足 → log.warn + return，不寫 outbox → bid 不進 matchEngine）
  → 修改 available/locked 欄位
  → walletRepository.save()
  → outboxRepository.save(AuctionBidConfirmedEvent)  ← 同一 transaction

[eap-wallet] OutboxPoller (每 500ms)
  → 讀取 PENDING outbox entries
  → rabbitTemplate.convertAndSend(auction.exchange, auction.bid.confirmed, event)
  → 標記 SENT

[eap-matchEngine] AuctionBidConfirmedListener.handleConfirmedBid()
  → objectMapper.writeValueAsString(event)
  → AuctionRedisService.collectBid() [Lua atomic]
      - status == OPEN 檢查
      - SISMEMBER 重複檢查
      - RPUSH bids:{side} + SADD participants

[排程 :10] AuctionSchedulerService.closeAndClear()
  → Redisson distributed lock
  → closeGate(): HSET config status CLOSED
  → getAllBids(): Lua LRANGE bids:buy + bids:sell
  → clearingService.clear(): 純算法
  → rabbitTemplate.convertAndSend(auction.exchange, auction.cleared, event)
  → [M-1 fix] cleanupAuction(): DEL 4 Redis keys
  → [M-2 fix] catch 時只在 clearedEventPublished==false 時發 FAILED event

[eap-order] AuctionResultListener.handleAuctionCleared()
  → findByAuctionId() 更新 session
  → [O-1 fix] findByAuctionId() batch 載入所有 bids 到 Map
  → 批次構建 resultEntities + updatedBids
  → auctionResultRepository.saveAll()
  → auctionBidRepository.saveAll()
  → messagingTemplate.convertAndSend("/topic/auction/result", resultDto)

[eap-wallet] AuctionSettlementListener.handleAuctionCleared()
  → for each result:
      [W-2 fix] TransactionTemplate.executeWithoutResult() 獨立 transaction
      → findByUserId()
      → settleBuyer() / settleSeller()
      → walletRepository.save()
```

**資料流結論**：鏈路完整，各節點資料傳遞無斷裂。wallet-first 保證只有鎖定資金的 bid 才進入拍賣。

---

## 整合風險點

### ~~RISK-1~~（已解決）：wallet-first + outbox 重構已消除此風險

**原問題**：eap-order 直接 Feign 呼叫 matchEngine 收集 bid 到 Redis，然後才非同步通知 wallet 鎖定資金。若 wallet 鎖定失敗，unfunded bid 已在 Redis 中參與清算。

**修復**：重構為 wallet-first + outbox pattern。bid 提交後先經 wallet 驗證並鎖定資金，鎖定成功才透過 outbox 將 `AuctionBidConfirmedEvent` 發送到 matchEngine。餘額不足的 bid 永遠不會到達 Redis。

### ~~RISK-2~~（已解決）：orphaned queue 已移除

**原問題**：`matchEngine.auctionBidSubmitted.queue` 綁定 `auction.bid.submitted` 但無 consumer，message 堆積。

**修復**：RabbitMQConfig 改為宣告 `matchEngine.auctionBidConfirmed.queue` 綁定 `auction.bid.confirmed`，由 `AuctionBidConfirmedListener` 消費。舊常量標記 `@Deprecated`。

### RISK-3：DLX 僅在 eap-order 宣告，啟動順序問題

**嚴重度**：低

**說明**：`FanoutExchange("order.dlx")` 和 `DEAD_LETTER_QUEUE` 只在 eap-order 宣告。若 eap-wallet 或 eap-matchEngine 在 eap-order 啟動前先啟動，這些 module 宣告的 queue（帶 `x-dead-letter-exchange=order.dlx`）可能因 DLX 不存在而啟動失敗。

**建議**：在 `docker-compose.yml` 確認 eap-order 先於 eap-wallet 和 eap-matchEngine 啟動（`depends_on`），或移至 eap-common 統一宣告。

### RISK-4：AuctionSettlementListener 的 TransactionTemplate 與 RabbitMQ ack 的事務邊界

**嚴重度**：低

**說明**：`handleAuctionCleared()` 使用 `TransactionTemplate` 逐筆獨立 transaction（W-2 fix 正確）。但 RabbitMQ message ack 在方法返回後才發出，與 DB transaction 不在同一事務範圍。若所有 settlement 完成後、ack 發出前服務崩潰，message 會被 requeue，導致重複結算。

**建議**：增加冪等保護（settlement 前先查 result 是否已 settled）。

### RISK-5：Lua 腳本的 participants Set 使用 userId 做全局去重，不區分 BUY/SELL

**嚴重度**：低（視業務規則而定）

**說明**：`collect_auction_bid.lua` 的 participants Set 不區分 buy/sell。同一用戶想同時提交 BUY 和 SELL bid 會被拒絕。但 DB `auction_bids` 有 `UNIQUE(auction_id, user_id, side)` constraint 允許此行為。Redis 邏輯比 DB constraint 更嚴格。

**建議**：確認業務規則。若不允許自我對沖，維持現狀；若允許，調整 Lua script 的 participants key 為 `auction:{id}:participants:{side}`。

### RISK-6：`AuctionSchedulerService.auctionEnabled` 與 Redis config 不同步

**嚴重度**：低

**說明**：`auctionEnabled` 從 `application.yml` 注入，`PUT /v1/auction/config` 修改 Redis 中的 `auctionEnabled`，兩者不同步。

**影響**：運維靈活性不足，非功能正確性問題。

---

## 未修正的已知問題（遺留自 Code Review）

| # | 嚴重度 | 說明 | 影響 |
|---|--------|------|------|
| C-1 | minor | `AuctionBidRequest.isValid()` 要求 auctionId 非空，matchEngine controller 允許空值並 auto-fill；兩端語意不一致 | MCP tool 已透過 auto-fill 繞過；實務上不觸發 |
| C-2 | suggestion | `AuctionBidResultEvent` 已定義但未使用 | 程式碼雜訊，無功能影響 |
| C-3 | suggestion | `AuctionBidResponse` message 中英文混用 | UX 一致性問題 |
| M-3 | minor | `PUT /v1/auction/config` 無 admin 權限驗證 | 任何人可修改價格上下限 |
| M-4 | minor | `initAuction()` 5 次獨立 HSET 非原子 | 極端情況下可見部分初始化 config |
| M-5 | minor | `auctionEnabled` 雙 source 不同步 | 動態關閉拍賣無效 |
| M-6 | suggestion | `buildCumulativeCurve()` 的 `isDemand` 參數未使用 | 程式碼雜訊 |
| O-3 | minor | DB persist 失敗時無 compensation | bid 在 Redis 但無本地記錄（wallet-first 後影響降低） |
| O-4 | minor | bid status 判斷條件缺少 comment | 可讀性問題 |
| O-5 | suggestion | `AuctionResultRes.java` 未使用 | 程式碼雜訊 |
| W-3 | minor | wallet 操作無樂觀鎖，CDA+Auction 並行可能 lost update | 高並發下有餘額計算錯誤風險 |

---

## 已修正的 Code Review 問題驗證

| # | 問題 | 修正方式 | 驗證結果 |
|---|------|----------|---------|
| M-1 | Redis key leak | `closeAndClear()` 成功發佈 event 後呼叫 `cleanupAuction()` | 通過 |
| M-2 | FAILED event 在 lock 外發佈 | `clearedEventPublished` flag，FAILED event 只在 flag 為 false 時發佈 | 通過 |
| W-1 | 餘額不足無回饋 | wallet-first 重構：餘額不足 → log.warn + return，不寫 outbox → bid 不進 matchEngine | 通過 |
| W-2 | 整方法同一 transaction | `TransactionTemplate.executeWithoutResult()` 逐筆獨立 transaction | 通過 |
| O-1 | N+1 查詢 | `findByAuctionId()` batch 載入 + `saveAll()` batch insert | 通過 |
| O-2 | 全表掃描 | `findByStatusOrderByCreatedAtDesc(String, Pageable)` + `PageRequest.of(0, limit)` | 通過 |
| P-1 | MCP tool 未設定 auctionId | `submitAuctionBid()` 先呼叫 `getAuctionStatus()` 取得 currentAuctionId | 通過 |

---

## Wallet-First + Outbox 重構驗證（Post-Integration Fix）

### 重構內容

原始流程（有 RISK-1）：
```
eap-order → Feign → matchEngine (bid 進 Redis) → 非同步 event → wallet (可能失敗)
```

重構後流程：
```
eap-order → event → wallet (lock + outbox, ATOMIC) → OutboxPoller → matchEngine (confirmed bid 進 Redis)
```

### 跨 Module 變更驗證

| Module | 變更 | 驗證 |
|--------|------|------|
| eap-common | 新增 `AuctionBidConfirmedEvent`、`AUCTION_BID_CONFIRMED_KEY`、`MATCH_ENGINE_AUCTION_BID_CONFIRMED_QUEUE` | 常量被所有 module 正確引用 ✓ |
| eap-order | `PlaceAuctionBidService` 移除 Feign call，只做 validate → persist → publish event | 不再直接呼叫 matchEngine ✓ |
| eap-wallet | `AuctionBidListener` lock + outbox 原子寫入；`OutboxPoller` 新增 event 反序列化 + exchange 路由 | `@Transactional` 保證原子性 ✓ |
| eap-matchEngine | 新增 `AuctionBidConfirmedListener`；RabbitMQ 綁定改為 `auction.bid.confirmed` | 只消費 confirmed events ✓ |

### Unit Test 結果

| Module | 測試數 | 結果 |
|--------|-------|------|
| eap-matchEngine | 48 | 全過 ✓ |
| eap-wallet（auction） | 17 | 全過 ✓ |
| eap-order（auction） | 8 | 全過 ✓ |

---

## 結論

**整體結論：PASS**

### 通過的項目

- Alignment Check：17/17 需求項目全部實作
- Event schema 一致（publisher schema = consumer 期望格式）
- RabbitMQ routing：exchange/routing key/queue 綁定全部正確，常量統一來自 eap-common
- Feign 介面：全部 URL 和參數匹配
- 資料流完整：wallet-first 保證 → Redis 收集 → 清算 → 雙 consumer（order + wallet）鏈路無斷裂
- Code Review 7 個 major 問題均已正確修正
- 清算演算法正確性（48 unit tests 全過）
- Wallet-first + outbox 重構消除 RISK-1（unfunded bid）和 RISK-2（orphaned queue）

### 建議後續 sprint 處理的風險

- RISK-3（低）：DLX 啟動順序 → docker-compose `depends_on`
- RISK-4（低）：settlement 冪等保護
- RISK-5（低）：Lua participants 去重邏輯與業務規則對齊
- W-3（minor）：wallet 樂觀鎖
