# Design Decisions: auction-risk-fixes

## RISK-3: DLX 宣告策略

- **決策**：各 module 各自宣告相同的 FanoutExchange("order.dlx") + Queue("order.dlq") + Binding
- **理由**：Spring AMQP 的 exchange/queue declare 是 idempotent 操作，多個 module 宣告相同名稱不衝突。各 module 自行宣告可消除啟動順序依賴，任一 module 先啟動都能正確建立 DLX。
- **替代方案**：(A) 統一由 eap-order 宣告，其他 module 靠 RabbitMQ 延遲建立 -- 不可靠，啟動順序不可控。(B) 改名為 shared.dlx -- 語意更好，但屬 refactor，不在本次 bugfix 範圍。
- **保留決策**：eap-order 原有的 DLX 宣告不刪除，三個 module 都宣告同一 DLX。DLX 命名 "order.dlx" 語意偏向 order module，記錄為後續 refactor 候選。

## RISK-4: 冪等保護策略

- **決策**：選項 A -- 在 wallet DB 新增 settlement_idempotency table
- **理由**：wallet 端自行管理冪等狀態，不引入跨 module Feign 依賴（避免選項 B 的 coupling）。獨立 table 比在 WalletEntity 加 JSON column 更清晰、可查詢（避免選項 C 的 schema 污染）。
- **替代方案**：(B) 查 eap-order 的 auction_results -- 增加跨 module coupling。(C) WalletEntity 加 JSON column -- schema 改動重且不易查詢。
- **UNIQUE constraint 雙保險**：settlement_idempotency 的 UK(auction_id, user_id, side) 提供 DB 層二次保護，即使應用層 check-then-insert 存在 race condition，DB constraint 也會攔截。

## RISK-5: UNIQUE constraint 變更

- **決策**：修改 DB constraint 對齊 Lua 邏輯（不修改 Lua）
- **理由**：Lua participants Set 的全局去重是正確的業務邏輯（不允許自我對沖），DB 端的 (auction_id, user_id, side) 允許同用戶雙向出價是 DB 定義落後於業務邏輯。修正 DB 對齊 Lua 是正確方向。
- **風險**：若生產環境已有同用戶雙向 bid 資料，migration 會失敗。但 Lua 已在 Redis 層攔截，實際不應存在此類資料。migration 前仍建議執行驗證 SQL。

## RISK-6: auctionEnabled 單一 source of truth

- **決策**：移除 @Value 注入，改讀 AuctionRedisService.getGlobalConfig()
- **理由**：PUT /v1/auction/config 寫入 Redis，scheduler 也從 Redis 讀取，確保單一 source of truth。getGlobalConfig() 已有 default 值處理（Redis hash 為空時回傳 auctionEnabled=true），不會因 Redis 無資料導致異常。
- **Trade-off**：每次 scheduled 執行都會多一次 Redis 讀取（getGlobalConfig），但 scheduler 每小時只觸發兩次（:00 和 :10），性能影響可忽略。

## W-3: 樂觀鎖重試策略

- **決策**：在 listener 方法內用 for-loop 重試，最多 3 次
- **理由**：不引入 spring-retry 依賴，保持簡單。AuctionBidListener 需從 @Transactional 改為 TransactionTemplate（與 AuctionSettlementListener 一致），才能在 retry 時重新開啟 transaction。
- **替代方案**：(A) @Retryable -- 需 spring-retry 依賴。(B) Pessimistic lock -- 增加 DB 鎖競爭。(C) 不 retry，靠 RabbitMQ requeue -- requeue 延遲較長且不可控。
- **Trade-off**：AuctionBidListener 改用 TransactionTemplate 需要較多程式碼改動，但統一了兩個 listener 的 transaction 管理模式。
