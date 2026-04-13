# Code Review: auction-risk-fixes

## Review 摘要
- **審查日期**：2026-04-13
- **審查範圍**：eap-matchEngine, eap-wallet, eap-order
- **最終決策**：approved

## Alignment Check

- **結果**：通過
- **說明**：比對 requirement.md + impact-analysis.md，所有 5 項風險（RISK-3, RISK-4, RISK-5, RISK-6, W-3）均有對應實作。受影響的 3 個 module（eap-matchEngine, eap-wallet, eap-order）皆有程式碼變更。Scope 未超出也未遺漏。

額外正向偏離：eap-order 的 `PlaceAuctionBidService` 新增了應用層 duplicate bid check（`existsByAuctionIdAndUserId`），這不在原始 implementation plan 中，但屬於 RISK-5 constraint 變更的合理防禦層（DB constraint 是最後防線，應用層提前攔截提供更好的錯誤訊息），屬正向偏離。

## 各 Module 審查結果

### eap-matchEngine

**變更檔案**：
- `RabbitMQConfig.java` — 新增 DLX bean 宣告（RISK-3）
- `AuctionSchedulerService.java` — 移除 @Value auctionEnabled，改讀 Redis（RISK-6）
- `AuctionSchedulerServiceTest.java` — 新增測試（RISK-6）

**問題**：無

**RISK-3 審查**：
- `deadLetterExchange()` 使用 `FanoutExchange(DEAD_LETTER_EXCHANGE)` -- 與 eap-order 已有宣告一致，Spring AMQP idempotent declare 機制保證不會衝突。
- `deadLetterQueue()` 使用 `QueueBuilder.durable(DEAD_LETTER_QUEUE).build()` -- 正確。
- `dlqBinding()` 將 queue bind 到 exchange -- 正確。
- Bean 注入：`dlqBinding` 方法參數名 `deadLetterQueue` 與 bean 方法名匹配，Spring 可正確解析（雖然 eap-wallet 版本使用了 `@Qualifier` 更為明確，但兩種做法均可正確運作）。

**RISK-6 審查**：
- 移除 `@Value("${auction.enabled:true}") private boolean auctionEnabled` -- 正確，消除了雙 source 問題。
- `openAuction()` 和 `closeAndClear()` 兩處均改為 `auctionRedisService.getGlobalConfig().isAuctionEnabled()` -- 正確。
- `getGlobalConfig()` 永不回傳 null（Redis 無資料時回傳 default config，`auctionEnabled` 預設 `true`），與原本 `@Value` 的預設值 `true` 一致 -- null safety 無問題。
- 測試覆蓋完整：disabled/enabled、lock 取得失敗、無 active auction、clearing exception 等 edge case 均有測試。

### eap-wallet

**變更檔案**：
- `RabbitMQConfig.java` — 新增 DLX bean 宣告（RISK-3）
- `SettlementIdempotencyEntity.java` — 新增 JPA entity（RISK-4）
- `SettlementIdempotencyRepository.java` — 新增 JPA repository（RISK-4）
- `AuctionSettlementListener.java` — 新增冪等 guard + retry loop（RISK-4, W-3）
- `AuctionBidListener.java` — 移除 @Transactional，改用 TransactionTemplate + retry loop（W-3）
- `db.changelog-wallet-init.xml` — 新增 wallet-011 changeSet（RISK-4）
- `AuctionSettlementListenerTest.java` — 新增冪等 + retry 測試
- `AuctionBidListenerTest.java` — 新增 retry 測試

**問題**：無 major issue。

**RISK-3 審查**：
- 與 eap-matchEngine 相同的 DLX bean 宣告。使用 `@Qualifier("deadLetterQueue")` 明確指定注入，更為安全。正確。

**RISK-4 審查**：
- `settlement_idempotency` table 結構正確：`(auction_id, user_id, side)` UNIQUE constraint 對應業務需求。
- Entity 的 `@UniqueConstraint` 與 Liquibase changeSet 的 `addUniqueConstraint` 一致。
- 冪等 guard 在 `txTemplate.executeWithoutResult()` 內，與 wallet save 和 idempotency record save 在同一 transaction 中 -- 正確，保證原子性。
- guard 邏輯順序正確：先 check `existsByAuctionIdAndUserIdAndSide()`，若已存在則 return（skip）；不存在則執行 settlement 並 save idempotency record。
- 注意：冪等 check 使用 `existsBy` 查詢（非 SELECT FOR UPDATE），理論上兩個並行 transaction 可能同時通過 check。但由於有 UNIQUE constraint 作為最後防線，第二個 insert 會拋出 `DataIntegrityViolationException`，被外層 `catch (Exception e)` 捕獲並 break。這是可接受的行為（雙重保護）。

**W-3 審查**：
- `AuctionSettlementListener`：retry loop 正確包裹 txTemplate，catch `ObjectOptimisticLockingFailureException` 後重試，max 3 次。失敗後 log error 但不 throw（settlement 失敗不影響其他 participant 的結算），符合業務需求。
- `AuctionBidListener`：移除 `@Transactional` 改用 `TransactionTemplate` + retry loop。max 3 次重試，最後一次失敗後 throw（bid locking 失敗應向上拋出，觸發 RabbitMQ nack/requeue），行為與 settlement 不同但合理（bid 可以 requeue 重試，settlement 需要繼續處理其他 participant）。
- Exception 類型 `ObjectOptimisticLockingFailureException`（Spring ORM wrapper）正確，這是 Spring Data JPA 在 @Version 衝突時拋出的實際 exception 類型。

### eap-order

**變更檔案**：
- `AuctionBidEntity.java` — @UniqueConstraint 從 3 欄改為 2 欄（RISK-5）
- `AuctionBidRepository.java` — `existsByAuctionIdAndUserIdAndSide` 改為 `existsByAuctionIdAndUserId`（RISK-5）
- `PlaceAuctionBidService.java` — 新增應用層 duplicate bid check（RISK-5 增強）
- `db.changelog-auction.xml` — 新增 auction-005 changeSet（RISK-5）
- `PlaceAuctionBidServiceTest.java` — 新增 duplicate bid 測試

**問題**：無 major issue。

**RISK-5 審查**：
- Liquibase changeSet `auction-005`：先 `dropUniqueConstraint(uk_auction_bids_auction_user_side)`，再 `addUniqueConstraint(uk_auction_bids_auction_user)` -- 正確。
- Entity `@UniqueConstraint` 從 `{"auction_id", "user_id", "side"}` 改為 `{"auction_id", "user_id"}` -- 正確對齊。
- Repository 方法從 `existsByAuctionIdAndUserIdAndSide` 改為 `existsByAuctionIdAndUserId` -- 正確。
- 應用層在 validation 之後、persist 之前做 duplicate check -- 位置正確。
- 測試覆蓋：same user same auction rejected, different user allowed, same user different auction allowed, buy-then-sell rejected -- 覆蓋完整。

## NFR Checklist

### 效能
- [x] N+1 Query -- 無問題。`existsByAuctionIdAndUserId` 是單筆查詢；settlement loop 中的 `findByUserId` 是逐筆處理，屬正常模式（每個 participant 一次 tx）。
- [x] 不必要的全表掃描 -- 無問題。`existsByAuctionIdAndUserId` 會利用新的 UNIQUE index；`existsByAuctionIdAndUserIdAndSide` 會利用 settlement_idempotency 的 UNIQUE index。
- [x] 同步阻塞呼叫 -- 無問題。所有修改都在 RabbitMQ listener 內（已是異步消費），無新增同步阻塞。

### 安全性
- [x] SQL Injection -- 無問題。所有查詢使用 Spring Data JPA 方法名推導，無原生 SQL。
- [x] 未驗證的使用者輸入 -- 無問題。本次修改不涉及新的使用者輸入處理。
- [x] 敏感資料外露 -- 無問題。Log 中僅記錄 auctionId、userId、side，無 PII 洩漏風險。
- [x] 權限檢查遺漏 -- 不適用。本次修改均為內部 listener 和 scheduler，不涉及新的 API 權限。

### 可維護性
- [x] 符合現有慣例 -- Entity 使用 Lombok @Data/@Builder，Repository 繼承 JpaRepository，Listener 使用 @RabbitListener + TransactionTemplate，與既有程式碼一致。
- [x] 程式碼結構清晰 -- 冪等 guard、retry loop、業務邏輯分層明確。
- [x] 無不必要的複雜度 -- 所有修改均為必要的 hardening，無 over-engineering。

## Issues Found

### Major（必須修）

無。

### Minor（建議修）

| # | Severity | 說明 | 檔案 |
|---|----------|------|------|
| 1 | minor | eap-matchEngine 的 `dlqBinding()` 未使用 `@Qualifier("deadLetterQueue")`，而 eap-wallet 有使用。兩者行為均正確（Spring 會 fallback 到 parameter name matching），但不一致。建議統一風格。 | eap-matchEngine RabbitMQConfig.java:109 |

### Suggestion（可選）

| # | Severity | 說明 | 檔案 |
|---|----------|------|------|
| 1 | suggestion | `AuctionSettlementListener` 的 `maxRetries = 3` 和 `AuctionBidListener` 的 `maxRetries = 3` 可考慮提取為 class-level 常量或配置項，避免 magic number 散落。 | eap-wallet AuctionSettlementListener.java, AuctionBidListener.java |
| 2 | suggestion | RISK-5 的 Liquibase migration `auction-005` 在生產環境執行前，建議先確認無違反新 constraint 的既有資料（`SELECT auction_id, user_id, COUNT(*) FROM order_service.auction_bids GROUP BY auction_id, user_id HAVING COUNT(*) > 1`）。此為運維注意事項，非程式碼問題。 | eap-order db.changelog-auction.xml |

## 結論

所有 5 項風險修復均正確實作，與 requirement.md 和 impact-analysis.md 完全對齊。NFR checklist 全部通過。無 major issue，僅有 1 個 minor（風格不一致）和 2 個 suggestion。決策：**approved**，進入 Phase 5 Integration Testing。
