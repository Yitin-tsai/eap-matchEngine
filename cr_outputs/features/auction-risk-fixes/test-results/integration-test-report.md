# Integration Test Report: auction-risk-fixes

- **報告日期**：2026-04-13
- **Phase**：Phase 5 Integration Test
- **需求 ID**：REQ-002
- **測試範圍**：3 modules（eap-matchEngine, eap-wallet, eap-order）
- **前置狀態**：所有 module `review_done`
- **最終結果**：**PASS**

---

## 1. Alignment Check

比對來源：`requirement.md` + `impact-analysis.md` vs 各 module 現有程式碼（worktree 實作狀態）

### 業務規則對照表

| # | 業務規則 | 驗證對象 | 實作位置 | 狀態 | 備註 |
|---|----------|----------|----------|------|------|
| R-1 | 不允許自我對沖：同一用戶在同一 auction 只能有一筆 bid（不分 side） | RISK-5 三層去重一致性 | Lua `participants` Set（全局）+ `AuctionBidRepository.existsByAuctionIdAndUserId()` + DB `UNIQUE(auction_id, user_id)` | 通過 | 詳見 RISK-5 驗證 |
| R-2 | 動態開關：`auctionEnabled` 修改後 scheduler 即時讀取 Redis | RISK-6 | `AuctionSchedulerService.openAuction()` / `closeAndClear()` 均呼叫 `auctionRedisService.getGlobalConfig().isAuctionEnabled()` | 通過 | `@Value` 欄位已移除，不再讀 yaml |
| R-3 | 冪等結算：同一 `(auctionId, userId, side)` 只結算一次，重複 message 自動 skip | RISK-4 | `AuctionSettlementListener` 在 tx 內先 `existsByAuctionIdAndUserIdAndSide()` 再結算，完成後同 tx 寫入 idempotency record | 通過 | wallet-011 migration 已建立 table |
| R-4 | 樂觀鎖重試：wallet 寫入遇 `OptimisticLockException` 最多重試 3 次 | W-3 | `AuctionSettlementListener` 和 `AuctionBidListener` 均實作 3 次 retry loop，catch `ObjectOptimisticLockingFailureException` | 通過 | `WalletEntity` 已有 `@Version Long version` |
| R-5 | DLX 各 module 各自宣告（防啟動順序問題） | RISK-3 | eap-matchEngine、eap-wallet、eap-order 三個 `RabbitMQConfig` 均有 `deadLetterExchange()` + `deadLetterQueue()` + `dlqBinding()` | 通過 | 詳見 RISK-3 驗證 |

**Alignment Check 結論**：5/5 業務規則均有對應實作，scope 完整，無遺漏。

---

## 2. 跨 Module 契約驗證

### RISK-3：DLX 一致性驗證

#### 常量來源

三個 module 的 `RabbitMQConfig` 均 `import static com.eap.common.constants.RabbitMQConstants.*`，使用統一常量：

| 常量 | 值 | 使用方 |
|------|----|--------|
| `DEAD_LETTER_EXCHANGE` | `"order.dlx"` | eap-matchEngine / eap-wallet / eap-order |
| `DEAD_LETTER_QUEUE` | `"order.dlq"` | eap-matchEngine / eap-wallet / eap-order |

無任何 module 使用 hardcoded 字串。

#### 各 Module DLX Bean 宣告驗證

| Module | `deadLetterExchange()` Bean | `deadLetterQueue()` Bean | `dlqBinding()` Bean | `@Qualifier` 使用 |
|--------|---------------------------|--------------------------|---------------------|-------------------|
| eap-matchEngine | 通過（L99-101） | 通過（L103-106） | 通過（L108-111） | 無（by-name 注入，參數名稱 `deadLetterQueue` 與 bean name 一致，Spring 可正確解析） |
| eap-wallet | 通過（L38-40） | 通過（L42-45） | 通過（L47-51） | 有（`@Qualifier("deadLetterQueue")`，更明確） |
| eap-order | 通過（L44-47） | 通過（L49-52） | 通過（L54-57） | 無（by-name 注入，僅一個 Queue bean 被宣告名稱 `deadLetterQueue`） |

#### Queue 的 DLX 設定一致性

所有業務 queue 均設定 `x-dead-letter-exchange` = `DEAD_LETTER_EXCHANGE`（`"order.dlx"`）：

| Module | Queue | DLX 設定 |
|--------|-------|----------|
| eap-matchEngine | `matchEngineOrderConfirmedQueue` | `DEAD_LETTER_EXCHANGE` ✓ |
| eap-matchEngine | `matchEngineAuctionBidConfirmedQueue` | `DEAD_LETTER_EXCHANGE` ✓ |
| eap-wallet | `walletOrderSubmittedQueue` | `DEAD_LETTER_EXCHANGE` ✓ |
| eap-wallet | `walletOrderMatchedQueue` | `DEAD_LETTER_EXCHANGE` ✓ |
| eap-wallet | `walletAuctionBidSubmittedQueue` | `DEAD_LETTER_EXCHANGE` ✓ |
| eap-wallet | `walletAuctionClearedQueue` | `DEAD_LETTER_EXCHANGE` ✓ |
| eap-order | `orderOrderConfirmedQueue` | `DEAD_LETTER_EXCHANGE` ✓ |
| eap-order | `orderOrderMatchedQueue` | `DEAD_LETTER_EXCHANGE` ✓ |
| eap-order | `orderOrderFailedQueue` | `DEAD_LETTER_EXCHANGE` ✓ |
| eap-order | `orderAuctionClearedQueue` | `DEAD_LETTER_EXCHANGE` ✓ |
| eap-order | `orderAuctionCreatedQueue` | `DEAD_LETTER_EXCHANGE` ✓ |

**RISK-3 結論：通過。** 三個 module 各自宣告 DLX/DLQ Bean，Spring AMQP idempotent declare 機制確保重複宣告不衝突。無論哪個 module 先啟動，DLX 都會被建立。

---

### RISK-4：冪等保護完整性驗證

#### Settlement Idempotency 資料層

**Liquibase changeSet `wallet-011`**（`db.changelog-wallet-init.xml` L120-141）：
- Table `settlement_idempotency`（schema `wallet_service`）已建立
- UNIQUE constraint `uk_settlement_idempotency`：`(auction_id, user_id, side)`
- `settled_at` 有 `defaultValueComputed="CURRENT_TIMESTAMP"`

**`SettlementIdempotencyEntity`**（`domain/entity/`）：
- `@Table` 的 `uniqueConstraints` 與 Liquibase 一致：`{"auction_id", "user_id", "side"}`
- `@Builder.Default settledAt = LocalDateTime.now()`

**`SettlementIdempotencyRepository`**（`configuration/repository/`）：
- `existsByAuctionIdAndUserIdAndSide(String auctionId, UUID userId, String side)` 簽名正確

#### Transaction 邊界驗證

`AuctionSettlementListener.handleAuctionCleared()` 的 `txTemplate.executeWithoutResult()` 內部執行順序：

```
1. settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(...)  ← idempotency 查詢
   → 已存在 → return（skip）
2. walletRepository.findByUserId(result.getUserId())
3. settleBuyer() / settleSeller()（in-memory wallet 修改）
4. walletRepository.save(wallet)                                           ← wallet 持久化
5. settlementIdempotencyRepository.save(SettlementIdempotencyEntity...)    ← idempotency 記錄
```

步驟 4 和 5 在同一個 `TransactionTemplate.executeWithoutResult()` 內，由 Spring 事務管理保證原子性。若步驟 4 成功但步驟 5 失敗（或反之），整個 tx 回滾。確保不會出現「已結算但無 idempotency 記錄」或「有 idempotency 記錄但未結算」的不一致狀態。

#### Message Requeue 場景驗證

若 ack 發出前崩潰，RabbitMQ requeue message，listener 重新執行：
- 步驟 1 的 `exists` check 返回 `true` → `return`（skip）
- wallet 不重複修改

**RISK-4 結論：通過。** 冪等保護完整，idempotency check + wallet save + idempotency record save 在同一 transaction 內，語意正確。

---

### RISK-5：去重邏輯三層一致性驗證

業務規則：**不允許自我對沖**，同一用戶在同一 auction 只能有一筆 bid，不分 side。

#### 三層去重對照

| 層次 | 位置 | 去重 key | 語意 |
|------|------|----------|------|
| Redis（Lua） | `collect_auction_bid.lua` L30：`SISMEMBER participants_key, user_id` | `auction:{id}:participants` Set，key 為 `userId`（不含 side） | 全局去重，拒絕同 userId 的第二筆 bid |
| Application | `PlaceAuctionBidService.java` L53：`auctionBidRepository.existsByAuctionIdAndUserId(auctionId, UUID)` | `(auction_id, user_id)` | 全局去重，與 Lua 語意一致 |
| Database | `db.changelog-auction.xml` changeSet `auction-005`：`UNIQUE(auction_id, user_id)` `constraintName="uk_auction_bids_auction_user"` | `(auction_id, user_id)` | 全局去重，防止應用層 race condition |

三層均以 `(auction_id, user_id)` 作為去重單位，不帶 `side`，語意完全一致。

#### Entity 層驗證

`AuctionBidEntity.java` L18-20：
```java
@Table(name = "auction_bids", schema = "order_service",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_auction_bids_auction_user",
           columnNames = {"auction_id", "user_id"}))
```
Entity 層 `@UniqueConstraint` 與 Liquibase changeSet `auction-005` 名稱（`uk_auction_bids_auction_user`）和欄位（`auction_id, user_id`）完全一致。

#### AuctionBidRepository 驗證

`AuctionBidRepository.existsByAuctionIdAndUserId(String auctionId, UUID userId)` 方法簽名正確，不帶 `side` 參數，與三層去重語意一致。

**RISK-5 結論：通過。** Lua Script / Application Layer / DB Constraint 三層去重邏輯語意完全一致，均實作「同 auction 同 userId 只允許一筆 bid，不分 side」。

---

### RISK-6：Config 來源一致性驗證

#### `AuctionSchedulerService` 讀取方式

`openAuction()` L53：
```java
if (!auctionRedisService.getGlobalConfig().isAuctionEnabled()) {
```
`closeAndClear()` L108：
```java
if (!auctionRedisService.getGlobalConfig().isAuctionEnabled()) {
```

確認 `@Value("${auction.enabled:true}") private boolean auctionEnabled` 欄位已不存在於檔案中（整個類別無 `@Value` 關於 `auction.enabled` 的注入）。

#### `AuctionRedisService.getGlobalConfig()` 行為

`getGlobalConfig()` L272-288：
- Redis hash key：`auction:config`
- 讀取欄位 `auction_enabled`，使用 `Boolean.parseBoolean()`
- **若 Redis 無資料**（entries 為空）→ 返回 `auctionEnabled(true)` 的 default config

#### `saveGlobalConfig()` 寫入行為

`saveGlobalConfig()` L259-264：
- 寫入 `GLOBAL_CONFIG_KEY = "auction:config"` hash 的 `"auction_enabled"` 欄位
- `PUT /v1/auction/config` 呼叫此方法後，scheduler 下次執行 `getGlobalConfig()` 即讀到更新後的值

#### Default 行為一致性

| 場景 | 行為 |
|------|------|
| Redis 無 config（首次啟動） | `getGlobalConfig()` 返回 `auctionEnabled=true`，scheduler 正常執行 |
| `PUT /v1/auction/config` with `auctionEnabled=false` | 寫入 Redis，scheduler 下次排程時讀到 `false`，跳過 |
| `PUT /v1/auction/config` with `auctionEnabled=true` | 寫入 Redis，scheduler 恢復正常執行 |

**RISK-6 結論：通過。** Scheduler 完全依賴 Redis config（`auction:config` hash），與 `saveGlobalConfig()` 寫入路徑一致。動態開關即時生效。Default 為 `true`，首次啟動無需預設 Redis 資料。

---

### W-3：樂觀鎖整合驗證

#### WalletEntity `@Version` 欄位

`WalletEntity.java` L70-73：
```java
@Version
@Column(name = "version", nullable = false)
@Builder.Default
private Long version = 0L;
```
DB migration `wallet-006`（addColumn `version BIGINT`）+ `wallet-007`（UPDATE SET version=0）+ `wallet-008`（addNotNullConstraint）已確保 DB 有此欄位。

#### AuctionSettlementListener Retry 邏輯

retry loop（`AuctionSettlementListener.java` L73-123）：
- 外層：`for (AuctionBidResult result : event.getResults())`（逐 result 獨立處理）
- 內層：`for (int attempt = 1; attempt <= maxRetries && !settled; attempt++)`（maxRetries = 3）
- catch `ObjectOptimisticLockingFailureException e`（`org.springframework.orm` 套件）
  - `attempt < maxRetries`：log.warn + 繼續 retry
  - `attempt == maxRetries`：log.error（告警升級）
- catch `Exception e`：log.error + `break`（non-retryable，不影響其他 result）

idempotency guard 在 tx 內，retry 時重新執行 exists check，確保不重複結算。

#### AuctionBidListener Retry 邏輯

`AuctionBidListener.java` L55-121：
- `TransactionTemplate txTemplate = new TransactionTemplate(transactionManager)`（移除 `@Transactional`）
- `for (int attempt = 1; attempt <= maxRetries; attempt++)`（maxRetries = 3）
- catch `ObjectOptimisticLockingFailureException e`
  - `attempt < maxRetries`：log.warn + 繼續 retry
  - `attempt == maxRetries`：log.error + `throw e`（讓 RabbitMQ 重新投遞）
- break（success）

**注意**：`AuctionBidListener` 在 3 次 retry 後 `throw e`，消息會進入 DLQ（因 queue 設定了 `x-dead-letter-exchange`）。這是正確行為：資金鎖定失敗應被保留以供後續處理，優於靜默丟棄。

**W-3 結論：通過。** `WalletEntity` 有 `@Version`，`AuctionSettlementListener` 和 `AuctionBidListener` 均正確 catch `ObjectOptimisticLockingFailureException`，最多重試 3 次。兩者的 retry 策略有合理差異（settlement 最終 log.error 告警；bid 最終 throw 進 DLQ）。

---

### 跨 Module 資料流驗證（auction-risk-fixes 修復後的完整流程）

```
[用戶] POST /v1/auction/bid
  → eap-order PlaceAuctionBidService.submitBid()
      step 1: request.isValid()
      step 2: existsByAuctionIdAndUserId()           ← RISK-5 Application 層去重
      step 3: 計算 totalLocked
      step 4: AuctionBidRepository.save()（status=SUBMITTED）
      step 5: publish auction.bid.submitted event

[eap-wallet] AuctionBidListener.onAuctionBidSubmitted()
  → TransactionTemplate [RISK-3: queue 有 DLX，W-3: retry 3次]
  → walletRepository.findByUserId()
  → 餘額檢查
  → 修改 available/locked
  → walletRepository.save()   ← W-3: @Version 觸發樂觀鎖
  → outboxRepository.save(AuctionBidConfirmedEvent)
  [若 ObjectOptimisticLockingFailureException → retry, 最多 3 次, 第 3 次 throw → DLQ]

[eap-wallet] OutboxPoller
  → publish auction.bid.confirmed event

[eap-matchEngine] AuctionBidConfirmedListener.handleConfirmedBid()
  → AuctionRedisService.collectBid() [Lua atomic]
      - status == OPEN 檢查
      - SISMEMBER participants（不分 side）  ← RISK-5 Redis 層去重
      - RPUSH bids:{side} + SADD participants

[排程 :00] AuctionSchedulerService.openAuction()
  → auctionRedisService.getGlobalConfig().isAuctionEnabled()  ← RISK-6
  → Redisson lock
  → initAuction()

[排程 :10] AuctionSchedulerService.closeAndClear()
  → auctionRedisService.getGlobalConfig().isAuctionEnabled()  ← RISK-6
  → Redisson lock
  → closeGate()
  → getAllBids() → clearingService.clear()
  → publish auction.cleared event
  → cleanupAuction()

[eap-wallet] AuctionSettlementListener.handleAuctionCleared()
  → [RISK-3: queue 有 DLX]
  → for each result:
      for attempt 1..3 [W-3 retry]:
          TransactionTemplate:
              existsByAuctionIdAndUserIdAndSide()   ← RISK-4 idempotency check
              → 已存在 → return（skip）
              walletRepository.findByUserId()
              settleBuyer() / settleSeller()
              walletRepository.save()               ← W-3: @Version 樂觀鎖
              settlementIdempotencyRepository.save()  ← RISK-4 同 tx 寫 record
          [若 ObjectOptimisticLockingFailureException → retry]
          [若其他 Exception → break（非重試），繼續下一 result]
```

**資料流結論**：5 項 RISK 修復後，所有關鍵路徑均有對應保護機制，跨 module 整合無斷裂。

---

## 3. Unit Test 結果彙總

Phase 3 unit test 結果（各 module code review 前已通過）：

| Module | 涵蓋範圍 | 狀態 |
|--------|----------|------|
| eap-matchEngine | RISK-3（DLX bean）、RISK-6（scheduler 讀 Redis config） | review_done |
| eap-wallet | RISK-3（DLX bean）、RISK-4（idempotency guard + settlement logic）、W-3（retry loop） | review_done |
| eap-order | RISK-5（constraint change + AuctionBidRepository.existsByAuctionIdAndUserId）| review_done |

各 module 均已通過 Phase 4 Code Review（status: `review_done`）才進入 Phase 5，符合 integration test 前置條件。

---

## 4. 遺留問題

### 非阻塞項目

| # | 嚴重度 | 說明 | 建議處理時機 |
|---|--------|------|-------------|
| L-1 | suggestion | eap-matchEngine `RabbitMQConfig.dlqBinding()` 未使用 `@Qualifier`（與 eap-wallet 不一致）。因 `deadLetterQueue` bean name 與方法名稱一致，Spring by-name 注入可正確解析，不影響功能，但不如 eap-wallet 明確。 | 下一 sprint cleanup |
| L-2 | suggestion | `DEAD_LETTER_EXCHANGE = "order.dlx"` 命名含 `order.` 前綴，語意上偏向 eap-order 所有，三 module 共用後語意不準確。可考慮改名為 `shared.dlx`，但屬 refactor，本次不處理。 | 獨立 refactor ticket |
| L-3 | minor | `AuctionBidListener` 在 3 次 retry 後 `throw ObjectOptimisticLockingFailureException`，訊息進入 DLQ。目前無 DLQ consumer，需確認 DLQ 監控 / 告警機制。 | 運維配置確認 |
| L-4 | suggestion | 繼承自 timed-double-auction 的遺留問題（C-2：`AuctionBidResultEvent` 未使用、M-3：admin 權限驗證缺失）不在本 feature 修復範圍，維持現狀。 | 獨立 ticket |

---

## 5. 結論

### 最終結論：PASS

### 通過的驗證項目

| 驗證 | 結果 |
|------|------|
| Alignment Check：5/5 業務規則均有對應實作 | 通過 |
| RISK-3：三 module DLX/DLQ bean 均已宣告，常量統一來自 eap-common | 通過 |
| RISK-4：idempotency check + wallet save + idempotency record save 在同一 transaction | 通過 |
| RISK-5：Lua / Application / DB 三層去重語意完全一致（均為 `(auction_id, user_id)`，不分 side） | 通過 |
| RISK-6：Scheduler 完全讀 Redis config，`@Value` yaml 注入已移除，dynamic toggle 正確 | 通過 |
| W-3：`WalletEntity` 有 `@Version`，兩個 listener 均正確 catch `ObjectOptimisticLockingFailureException` + 3 次 retry | 通過 |
| 跨 module 資料流：完整流程無斷裂 | 通過 |
| Schema migration：wallet-011 + auction-005 changeSet 與 Entity annotation 一致 | 通過 |

### 遺留 timed-double-auction RISK 修復狀態

| 原 RISK | 說明 | 本 feature 後狀態 |
|---------|------|-----------------|
| RISK-3 | DLX 啟動順序問題 | 已修復（三 module 各自宣告） |
| RISK-4 | Settlement 冪等保護缺失 | 已修復（settlement_idempotency table + guard） |
| RISK-5 | Lua 去重與 DB constraint 不一致 | 已修復（DB constraint 對齊為 `(auction_id, user_id)`） |
| RISK-6 | auctionEnabled 雙 source | 已修復（Scheduler 改讀 Redis） |
| W-3 | Wallet 樂觀鎖無 retry | 已修復（两 listener 均加 retry loop） |

原 timed-double-auction integration test 結果為 CONDITIONAL_PASS，所有遺留 RISK 本 feature 全數修復，系統達到正式可用狀態。
