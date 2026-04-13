# Impact Analysis: auction-risk-fixes

- **分析日期**：2026-04-13
- **分析者**：gs-analyst
- **需求 ID**：REQ-002
- **Feature**：auction-risk-fixes

---

## 1. 需求摘要

修復 timed-double-auction 整合測試（CONDITIONAL_PASS）遺留的 5 項風險，使系統達到正式可用狀態：

| # | 風險 | 說明 | 嚴重度 |
|---|------|------|--------|
| RISK-3 | DLX 啟動順序 | `order.dlx` FanoutExchange 僅在 eap-order 宣告，eap-wallet / eap-matchEngine 的 queue 設定 `x-dead-letter-exchange=order.dlx`，若這兩個 module 先啟動則因 DLX 不存在而失敗 | 低 |
| RISK-4 | AuctionSettlementListener 缺冪等保護 | RabbitMQ ack 在方法返回後才發出，ack 前崩潰導致 message requeue，重複執行 settlement | 低 |
| RISK-5 | Lua participants Set 不區分 BUY/SELL | `collect_auction_bid.lua` 以 `userId` 做全局去重，同一用戶無法同時出 BUY 和 SELL bid；業務決策為**不允許自我對沖**，故修改 DB constraint 從 `(auction_id, user_id, side)` 改為 `(auction_id, user_id)` 對齊 Lua 邏輯 | 低 |
| RISK-6 | auctionEnabled 雙 source | `AuctionSchedulerService.auctionEnabled` 從 `application.yml` 注入，`PUT /v1/auction/config` 寫入 Redis；兩者不同步，動態關閉拍賣無效 | 低 |
| W-3 | wallet 操作缺樂觀鎖衝突處理 | `WalletEntity` 已有 `@Version` 欄位（wallet-006~008 migration 已完成），但 `AuctionSettlementListener` 和 `AuctionBidListener` 的寫入路徑未處理 `OptimisticLockException`，CDA + Auction 並行可能 lost update | minor |

---

## 2. 受影響 Module 清單

### 逐一判斷（6 個 managed modules）

#### eap-matchEngine — 受影響
**RISK-3**：`RabbitMQConfig.java` 宣告了 `matchEngineAuctionBidConfirmedQueue` 和 `matchEngineOrderConfirmedQueue`，兩個 queue 均設定 `x-dead-letter-exchange=order.dlx`，但此 module 未自行宣告 DLX FanoutExchange。需在本 module 的 `RabbitMQConfig` 中補宣告 DLX。

**RISK-5**：`collect_auction_bid.lua` 使用單一 `participants` key（`auction:{id}:participants`）做跨 buy/sell 全局去重，是 RISK-5 的業務邏輯核心所在。需確認此行為為預期設計（不允許自我對沖），無需修改 Lua 本身。

**RISK-6**：`AuctionSchedulerService` 的 `auctionEnabled` 欄位以 `@Value("${auction.enabled:true}")` 注入，不讀 Redis。`AuctionRedisService.getGlobalConfig()` 已可回傳 `auctionEnabled`，但 scheduler 未使用。需修改 `openAuction()` 和 `closeAndClear()` 改讀 Redis config。

影響檔案：
- `src/main/java/com/eap/eap_matchengine/configuration/config/RabbitMQConfig.java`（RISK-3）
- `src/main/java/com/eap/eap_matchengine/application/AuctionSchedulerService.java`（RISK-6）

#### eap-wallet — 受影響
**RISK-3**：`RabbitMQConfig.java` 宣告了 `walletAuctionBidSubmittedQueue`、`walletAuctionClearedQueue`、`walletOrderSubmittedQueue`、`walletOrderMatchedQueue`，四個 queue 均設定 `x-dead-letter-exchange=order.dlx`，但此 module 未自行宣告 DLX FanoutExchange。需補宣告。

**RISK-4**：`AuctionSettlementListener.handleAuctionCleared()` 缺乏冪等保護。當前邏輯：收到 `AuctionClearedEvent` 後逐筆對 `WalletEntity` 執行 `settleBuyer()` / `settleSeller()` 並 `walletRepository.save()`，無任何已結算狀態檢查。需在結算前查詢 `auction_results`（eap-order DB）或在 wallet 端新增已處理 event 記錄。

**W-3**：`AuctionSettlementListener` 和 `AuctionBidListener` 呼叫 `walletRepository.save(wallet)` 時，若觸發 `OptimisticLockException`（因 CDA 與 Auction 並行寫同一 wallet），當前沒有 retry 邏輯，exception 僅被 catch 記錄 log，會導致 lost update 而非失敗告警。需在 catch 中加入 retry 或改用 pessimistic lock。

影響檔案：
- `src/main/java/com/eap/eap_wallet/configuration/config/RabbitMQConfig.java`（RISK-3）
- `src/main/java/com/eap/eap_wallet/application/AuctionSettlementListener.java`（RISK-4、W-3）
- `src/main/java/com/eap/eap_wallet/application/AuctionBidListener.java`（W-3，需確認）

#### eap-order — 受影響
**RISK-3**：`RabbitMQConfig.java` 已宣告 `FanoutExchange("order.dlx")` 和 `DEAD_LETTER_QUEUE`，此 module 是 DLX 的唯一宣告者。本次修復後可移除此 module 的獨佔責任（各 module 各自宣告），或保留由 eap-order 宣告、其他 module 僅做 `durable` 宣告（confirm-only 方式）。兩種做法均可，需在 implementation guide 中決策。

**RISK-5**：`auction_bids` table 的 DB UNIQUE constraint 為 `(auction_id, user_id, side)`（定義於 `AuctionBidEntity.java` 的 `@UniqueConstraint` 和 `db.changelog-auction.xml` changeSet `auction-002`），需修改為 `(auction_id, user_id)` 對齊 Lua 去重邏輯。需新增 Liquibase migration。

影響檔案：
- `src/main/java/com/eap/eap_order/configuration/config/RabbitMQConfig.java`（RISK-3，視實作策略）
- `src/main/java/com/eap/eap_order/domain/entity/AuctionBidEntity.java`（RISK-5，修改 `@UniqueConstraint`）
- `src/main/resources/db/changelog/db.changelog-auction.xml`（RISK-5，新增 changeSet 修改 constraint）

#### eap-common — 受影響（間接）
`RabbitMQConstants.java` 定義 `DEAD_LETTER_EXCHANGE = "order.dlx"` 和 `DEAD_LETTER_QUEUE = "order.dlq"`，三個 module 的 `RabbitMQConfig` 均引用這些常量。RISK-3 修復後，此常量定義本身無需變更，但需確認命名依然合理（DLX 名稱目前帶 `order.` 前綴，語意上偏向 order module 所有，可考慮統一改名為 `shared.dlx`，但此為額外 refactor，非本次 RISK-3 必要範圍）。

**本次不需修改 eap-common**，僅需各 module 各自在其 `RabbitMQConfig` 中宣告 `FanoutExchange(DEAD_LETTER_EXCHANGE)`。

#### eap-mcp — 不受影響
掃描確認：eap-mcp 不宣告任何 RabbitMQ queue，無 DLX 設定；不持久化 wallet 資料；`AuctionMcpTool` 只透過 Feign 呼叫 eap-order 的 MCP API，不直接消費 RabbitMQ。5 項風險均不涉及 eap-mcp。

#### eap-ai-client — 不受影響
掃描確認：eap-ai-client 無任何 auction、wallet、DLX、settlement 相關程式碼。5 項風險均不涉及 eap-ai-client。

---

## 3. 建議 Main Module

**eap-matchEngine**

理由：RISK-3 和 RISK-6 的核心修改均在 eap-matchEngine（scheduler 改讀 Redis、補宣告 DLX）；Lua 邏輯確認（RISK-5 業務決策）也在此 module。timed-double-auction feature 的 main module 亦為 eap-matchEngine，保持一致有利於 cr_outputs 集中管理。

---

## 4. 涉及的 API Endpoint

本次修復均為內部邏輯修改，無新增 Public API。現有 endpoint 行為不變，但以下 endpoint 的語意一致性因 RISK-5 改變：

| Endpoint | Module | 變更類型 | 說明 |
|----------|--------|----------|------|
| `POST /v1/auction/bid` | eap-matchEngine | 行為變更（非 API breaking） | `collect_auction_bid.lua` 的 duplicate 判斷已是 `userId` 全局去重，DB constraint 修改後語意對齊。同一用戶每場拍賣只能有一筆 bid（不分 side）。|
| `PUT /v1/auction/config` | eap-matchEngine | 行為修復 | 修改後 `auctionEnabled` 更新 Redis 後，scheduler 會即時讀取；原本不同步的問題修復。無 response schema 變更。|

**Breaking Change 評估**：無 API response schema 變更，無 breaking change。RISK-5 的 constraint 變更屬於業務規則收緊（從允許同用戶 BUY+SELL 到禁止），是業務層 breaking change，需在 implementation plan 說明並確認業務方已知悉。

---

## 5. 預計異動檔案清單

### RISK-3：DLX 各 Module 各自宣告

| 檔案 | Module | 異動類型 | 說明 |
|------|--------|----------|------|
| `src/main/java/com/eap/eap_matchengine/configuration/config/RabbitMQConfig.java` | eap-matchEngine | 修改 | 新增 `deadLetterExchange()` FanoutExchange Bean + `deadLetterQueue()` + `dlqBinding()` |
| `src/main/java/com/eap/eap_wallet/configuration/config/RabbitMQConfig.java` | eap-wallet | 修改 | 同上，新增 DLX 相關 Bean |

備註：eap-order 的 `RabbitMQConfig.java` 已有正確宣告，無需修改。eap-mcp 無 queue 宣告，無需修改。

### RISK-4：AuctionSettlementListener 冪等保護

| 檔案 | Module | 異動類型 | 說明 |
|------|--------|----------|------|
| `src/main/java/com/eap/eap_wallet/application/AuctionSettlementListener.java` | eap-wallet | 修改 | 在 `txTemplate.executeWithoutResult()` 內，於 `walletRepository.save()` 前先查詢該 `(auctionId, userId, side)` 是否已有結算記錄；若已有則 skip，防止重複結算 |

冪等保護策略選項（由 architect 決策）：
- **選項 A（建議）**：查詢 wallet 端自建的 `settlement_idempotency` table（需新增 table）
- **選項 B**：wallet 透過 Feign 查詢 eap-order 的 `auction_results` 確認是否已存在對應 result（增加跨 module coupling）
- **選項 C**：在 `WalletEntity` 增加 `auction_settled_events` JSON column 記錄已處理的 auctionId（schema 改動較重）

### RISK-5：Lua participants Set 對齊 DB constraint

| 檔案 | Module | 異動類型 | 說明 |
|------|--------|----------|------|
| `src/main/java/com/eap/eap_order/domain/entity/AuctionBidEntity.java` | eap-order | 修改 | `@UniqueConstraint` 從 `columnNames = {"auction_id", "user_id", "side"}` 改為 `columnNames = {"auction_id", "user_id"}` |
| `src/main/resources/db/changelog/db.changelog-auction.xml` | eap-order | 修改（新增 changeSet） | 新增 changeSet `auction-005`：`dropUniqueConstraint` 移除 `uk_auction_bids_auction_user_side`，再 `addUniqueConstraint` 新增 `uk_auction_bids_auction_user`（`auction_id, user_id`） |

Lua script 本身（`collect_auction_bid.lua`）**不需修改**，其 `participants` Set 全局去重行為即為目標業務邏輯。

### RISK-6：auctionEnabled 改讀 Redis

| 檔案 | Module | 異動類型 | 說明 |
|------|--------|----------|------|
| `src/main/java/com/eap/eap_matchengine/application/AuctionSchedulerService.java` | eap-matchEngine | 修改 | 移除 `@Value("${auction.enabled:true}") boolean auctionEnabled`；在 `openAuction()` 和 `closeAndClear()` 方法開頭改呼叫 `auctionRedisService.getGlobalConfig().isAuctionEnabled()` 做開關判斷 |

備註：`AuctionRedisService.saveGlobalConfig()` 已正確持久化 `auction_enabled` 到 Redis hash `auction:config`，`getGlobalConfig()` 也已正確讀回，無需修改這兩個方法。

### W-3：wallet 操作樂觀鎖衝突處理

| 檔案 | Module | 異動類型 | 說明 |
|------|--------|----------|------|
| `src/main/java/com/eap/eap_wallet/application/AuctionSettlementListener.java` | eap-wallet | 修改 | 在 `txTemplate.executeWithoutResult()` 外層 catch 中，對 `OptimisticLockException` 加入 retry 邏輯（最多 3 次），超過次數後升級 log level 為 error 並發告警 |
| `src/main/java/com/eap/eap_wallet/application/AuctionBidListener.java` | eap-wallet | 修改（需確認）| 同樣需確認 `onAuctionBidSubmitted()` 是否已有 `OptimisticLockException` 處理；若無則補充 |

備註：`WalletEntity.java` 已有 `@Version Long version` 欄位，DB migration wallet-006~008 已完成，**無需再做 schema 修改**。

---

## 6. Schema 異動

### RISK-5：auction_bids UNIQUE constraint 變更（eap-order DB）

**Migration 工具**：Liquibase（與現有 `db.changelog-auction.xml` 一致）

**異動內容**：

```xml
<!-- 新增於 db.changelog-auction.xml -->
<changeSet id="auction-005" author="eap">
    <comment>RISK-5: 修改 auction_bids UNIQUE constraint 從 (auction_id, user_id, side) 改為 (auction_id, user_id)，對齊 Lua participants Set 的全局去重邏輯（不允許自我對沖）</comment>
    <dropUniqueConstraint
        tableName="auction_bids"
        schemaName="order_service"
        constraintName="uk_auction_bids_auction_user_side"/>
    <addUniqueConstraint
        tableName="auction_bids"
        schemaName="order_service"
        columnNames="auction_id, user_id"
        constraintName="uk_auction_bids_auction_user"/>
</changeSet>
```

**影響評估**：
- 若生產環境已有 `(auction_id, user_id, side)` 為 unique 的歷史資料（同一用戶在同一 auction 同時有 BUY 和 SELL），`dropUniqueConstraint` 後新的 constraint 將無法 apply（duplicate key violation）。由於 Lua 已在 Redis 層防止此情況，實際上不應存在此類資料，但需在 migration 前確認。
- **建議**：migration 前執行確認 SQL：`SELECT auction_id, user_id, COUNT(*) FROM order_service.auction_bids GROUP BY auction_id, user_id HAVING COUNT(*) > 1`

### RISK-4 冪等保護（eap-wallet DB，視選項決策）

若採用選項 A（建議），需新增 table：

```xml
<changeSet id="wallet-011" author="eap">
    <comment>RISK-4: 新增 settlement_idempotency table 防止 AuctionSettlementListener 重複結算</comment>
    <createTable tableName="settlement_idempotency" schemaName="wallet_service">
        <column name="id" type="BIGSERIAL">
            <constraints primaryKey="true" nullable="false"/>
        </column>
        <column name="auction_id" type="VARCHAR(20)">
            <constraints nullable="false"/>
        </column>
        <column name="user_id" type="UUID">
            <constraints nullable="false"/>
        </column>
        <column name="side" type="VARCHAR(4)">
            <constraints nullable="false"/>
        </column>
        <column name="settled_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
            <constraints nullable="false"/>
        </column>
    </createTable>
    <addUniqueConstraint
        tableName="settlement_idempotency"
        schemaName="wallet_service"
        columnNames="auction_id, user_id, side"
        constraintName="uk_settlement_idempotency"/>
</changeSet>
```

此 schema 異動需 architect 在確認點 2 決策冪等保護選項後確定是否採用。

### W-3（無新增 schema 異動）

`WalletEntity` 的 `@Version` 欄位及對應 DB column 已由 wallet-006~008 migration 完成，**本次無需新增 migration**。

---

## 7. Breaking Change 風險

| 類型 | 描述 | 影響範圍 | 風險等級 |
|------|------|----------|---------|
| 業務規則 breaking | RISK-5：同一用戶在同一 auction 不再允許同時有 BUY 和 SELL bid。原 DB constraint 允許此行為，修改後統一禁止。 | 任何呼叫 `POST /v1/auction/bid` 嘗試同用戶雙向出價的 client（現有 Lua 已攔截，實際影響為零，但語意確認必要） | 低（已被 Lua 層攔截） |
| 運維行為 breaking | RISK-6：修復後 `PUT /v1/auction/config` 的 `auctionEnabled=false` 將真正生效（先前寫入 Redis 但 scheduler 仍讀 yaml，實際上無效）。若有依賴「改 Redis 不影響 scheduler」的測試或腳本需調整 | eap-matchEngine scheduler 行為 | 低（修復行為符合設計意圖） |
| API response schema | 所有修復均不變更任何 API 的 request/response schema | N/A | 無 |

---

## 8. 開發慣例 confidence 警示

### eap-wallet：冪等保護選項尚未確認（low confidence）

**警示**：RISK-4 冪等保護的實作方案（選項 A/B/C）尚未決策。選項 A 需新增 `settlement_idempotency` table（schema migration），選項 B 引入跨 module Feign 依賴，選項 C 修改 `WalletEntity`。三種方案對 eap-wallet 的 module 架構影響不同，需在確認點 2（實作計畫確認）前決定。

**建議**：採用選項 A，在 wallet DB 內部解決冪等，不引入跨 module coupling。

### eap-order：RabbitMQConfig DLX 保留策略（low confidence）

**警示**：RISK-3 修復後，eap-order 的 `RabbitMQConfig` 仍保留 DLX 宣告（原有邏輯），eap-matchEngine 和 eap-wallet 也各自新增宣告。三個 module 重複宣告同一 FanoutExchange 和 Queue 在 Spring AMQP 中為合法行為（idempotent declare），但需確認命名 `DEAD_LETTER_EXCHANGE = "order.dlx"` 的語意是否需要在後續 refactor 中調整為更通用的名稱（如 `shared.dlx`）。本次不處理，僅記錄。

---

## 9. 併發風險警示

### timed-double-auction 與 auction-risk-fixes 存在 Module 重疊

| Feature | Phase | Affected Modules |
|---------|-------|-----------------|
| timed-double-auction | integration_testing（CONDITIONAL_PASS） | eap-common, eap-matchEngine, eap-order, eap-wallet, eap-mcp |
| auction-risk-fixes（本 feature） | analyzing | eap-matchEngine, eap-wallet, eap-order |

**重疊 modules**：eap-matchEngine、eap-wallet、eap-order（全部 3 個受影響 module 均與 timed-double-auction 重疊）

**風險評估**：
- timed-double-auction 目前 phase 為 `integration_testing`，`integration_test_result = "CONDITIONAL_PASS"`，各 module status 均為 `integration_test_done`。依照流程，後續應等待 `phase: "done"` 確認或使用者手動處理。
- auction-risk-fixes 的所有修改均在 timed-double-auction 已有程式碼的基礎上進行（修 bug/強化），而非新功能。兩者若同時開發，需注意 worktree 管理和 merge 順序。
- **建議**：先確認 timed-double-auction 的 integration_testing 結論（若已 PASS 可 close），再開發 auction-risk-fixes，避免雙向修改同一檔案造成 merge conflict。特別是以下高衝突檔案：
  - `eap-matchEngine/src/main/java/.../AuctionSchedulerService.java`
  - `eap-wallet/src/main/java/.../AuctionSettlementListener.java`
  - `eap-order/src/main/resources/db/changelog/db.changelog-auction.xml`

---

## 10. Lock 檔狀態

- **dependencies.lock**：不存在，建議先執行 bootstrap。本分析以 openapi spec + source code 直接掃描為依據，結果完整。
- **schema.lock（eap-matchEngine）**：不存在。已直接掃描 `application.yml` 和 Redis key 結構，確認無 DB schema（matchEngine 不使用 RDBMS）。
- **schema.lock（eap-order）**：不存在。已直接掃描 `db.changelog-auction.xml` 和 Entity 類別，結果可信。
- **schema.lock（eap-wallet）**：不存在。已直接掃描 `db.changelog-wallet-init.xml` 和 `WalletEntity.java`，確認 `@Version` 欄位已存在（wallet-006）。

以上 lock 檔缺失不影響本次分析準確性，但建議在 Phase 1-2 前補齊 bootstrap，以利後續 feature 分析。
