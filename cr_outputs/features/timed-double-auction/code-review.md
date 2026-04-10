# Code Review: timed-double-auction

## Review 摘要
- **審查日期**：2026-04-09
- **審查範圍**：eap-common, eap-matchEngine, eap-order, eap-wallet, eap-mcp（5 modules）
- **測試結果**：102/102 ALL PASSED（eap-wallet 17, eap-order 37, eap-matchEngine 48）
- **最終決策**：**changes_requested**

---

## Alignment Check

- **結果**：通過（scope 完整，無遺漏）
- **說明**：

比對 `requirement.md` + `impact-analysis.md`，所有需求項目均已實作：

| 需求項目 | 實作狀態 |
|----------|----------|
| 每小時 :00 開啟拍賣 / :10 清算 | AuctionSchedulerService @Scheduled cron 正確 |
| 密封出價（僅顯示參與人數） | Redis Lua 原子收集 + getParticipantCount |
| 階梯式出價 (stepwise bid) | AuctionBidRequest.BidStep + expandBids |
| Uniform Price (MCP) 清算 | AuctionClearingService.clear() 正確實作 |
| 成交量最大化 + 中間價規則 | 掃描所有 price level，取 max volume，midpoint |
| Pro-rata 邊際分配 | proRataAllocate 正確實作 |
| 不可修改/刪除出價 | Lua script 檢查 duplicate，無 update/delete API |
| Admin 可配置價格上下限 | PUT /v1/auction/config + Redis global config |
| Buyer lock = sum(price*amount) | PlaceAuctionBidService + AuctionBidListener |
| Seller lock = sum(amount) | PlaceAuctionBidService + AuctionBidListener |
| 結算退差 | AuctionSettlementListener 正確計算 refund |
| 未成交全額解鎖 | AuctionSettlementListener 正確處理 |
| DB 持久化 (3 tables) | Liquibase changelog + JPA entities |
| WebSocket 推送 | /topic/auction/status + /topic/auction/result |
| MCP tool 暴露 | AuctionMcpTool 3 tools |
| 與 CDA 並存 | 獨立 exchange, 獨立 event, 不影響現有 CDA |

**5 個 affected modules 全部有對應程式碼變更，scope 無超出或遺漏。**

---

## 各 Module 審查結果

### eap-common

**變更檔案**（10 files）：
- 2 enums: AuctionStatus, TradingMode
- 4 events: AuctionCreatedEvent, AuctionBidSubmittedEvent, AuctionClearedEvent, AuctionBidResultEvent
- 6 DTOs: AuctionBidRequest, AuctionBidResponse, AuctionStatusDto, AuctionResultDto, ClearingResultDto, AuctionConfigDto
- 1 modified: RabbitMQConstants

**問題**：

| # | Severity | 說明 | 檔案 |
|---|----------|------|------|
| C-1 | minor | AuctionBidRequest.isValid() 要求 auctionId 不為空，但 matchEngine AuctionController.submitBid() 允許空 auctionId 並自動填入 currentAuctionId。eap-order 的 PlaceAuctionBidService 直接傳遞 request，若 auctionId 為空會在 isValid() 被擋住。兩端驗證邏輯不一致，但因為 eap-order controller 的 PlaceAuctionBidReq 有 @NotNull auctionId，實務上不會觸發此問題。建議統一語意。 | AuctionBidRequest.java:49-51, AuctionController.java (matchEngine):50-57 |
| C-2 | suggestion | AuctionBidResultEvent 已定義但未在任何 module 中使用（未被 publish 或 consume）。若為預留設計，建議加註釋說明；否則應移除以避免混淆。 | AuctionBidResultEvent.java |
| C-3 | suggestion | AuctionBidResponse 混用中英文 message（"投標提交成功" vs "Auction gate is closed"）。建議統一語言。 | AuctionBidResponse.java:43 |

---

### eap-matchEngine

**變更檔案**（9 files）：
- AuctionClearingService.java（核心演算法）
- AuctionRedisService.java（Redis 操作）
- AuctionSchedulerService.java（排程）
- AuctionController.java（REST API）
- RabbitMQConfig.java（RabbitMQ 設定）
- collect_auction_bid.lua, get_all_auction_bids.lua
- application.yml

**問題**：

| # | Severity | 說明 | 檔案 |
|---|----------|------|------|
| M-1 | **major** | AuctionClearingService.getDemandAtPrice() 使用 TreeMap.ceilingEntry() 查詢需求曲線。雖然當前邏輯正確（已通過 48 tests），但 `buildCumulativeCurve()` 對同一 price level 多個 FlatBid 會覆蓋 cumQty（TreeMap.put 覆蓋同 key），這恰好正確因為是累積值。然而，若未來 demand curve 的 key 有非整數（如浮點），TreeMap 的行為可能不同。建議加 comment 說明此 TreeMap 行為依賴。 | AuctionClearingService.java:180-188 |
| M-2 | **major** | AuctionSchedulerService.closeAndClear() 在清算完成後未清理 Redis keys。隨時間累積，`auction:{auctionId}:*` keys 會永久留存。AuctionRedisService 已有 cleanupAuction() 方法但未被呼叫。應在清算完成且 event 發佈成功後呼叫 cleanupAuction()。 | AuctionSchedulerService.java:123-164 |
| M-3 | **major** | AuctionSchedulerService.closeAndClear() 的 catch(Exception) 區塊嘗試發佈 FAILED event，但此時若 auctionId 不為 null，downstream 會同時收到 exception context 中的部分狀態和 FAILED event。且此 catch 區塊在 lock 的 try-finally 之外，意味著 lock 已被釋放。如果清算過程中 exception 發生在 publishEvent 之後、lock 釋放之前，可能導致 FAILED event 覆蓋正常的 CLEARED event。建議將 error handling 移入 lock scope 內，並在 FAILED event 前檢查是否已成功發佈 CLEARED event。 | AuctionSchedulerService.java:173-193 |
| M-4 | minor | AuctionController.updateConfig() 無權限檢查。任何人都可以 PUT /v1/auction/config 修改價格上下限。需求明確說是「Admin-configurable」，應加 admin 權限驗證。 | AuctionController.java:149-160 |
| M-5 | minor | AuctionRedisService.initAuction() 中設定 Redis config hash 使用多個獨立 HSET 呼叫而非 HMSET/putAll，非原子操作。在極端情況下可能看到部分初始化的 config。建議使用 putAll 或 Lua script。 | AuctionRedisService.java:88-93 |
| M-6 | suggestion | AuctionSchedulerService 的 auctionEnabled 欄位使用 @Value 注入，但 AuctionRedisService.getGlobalConfig() 也有 auctionEnabled 欄位。兩者不同步：修改 Redis config 的 auctionEnabled 不會影響 @Scheduled 的行為。建議統一使用 Redis config 或 application.yml，避免混淆。 | AuctionSchedulerService.java:42-43 |

---

### eap-order

**變更檔案**（16 files）：
- PlaceAuctionBidService.java, AuctionResultListener.java, AuctionStatusService.java
- AuctionController.java, McpApiController.java (modified)
- EapMatchEngine.java (Feign, modified)
- 3 entities: AuctionSessionEntity, AuctionBidEntity, AuctionResultEntity
- 3 repositories
- PlaceAuctionBidReq.java, AuctionResultRes.java
- db.changelog-auction.xml, db.changelog-master.xml (modified)
- RabbitMQConfig.java (modified)

**問題**：

| # | Severity | 說明 | 檔案 |
|---|----------|------|------|
| O-1 | **major** | AuctionResultListener.handleAuctionCleared() 中，對每個 result 都呼叫 `auctionBidRepository.findByAuctionIdAndUserId()` 和 `auctionResultRepository.save()`，形成 N+1 查詢模式。假設一場拍賣有 100 位參與者，將產生 100 次 SELECT + 100 次 INSERT + 100 次 UPDATE。應使用 batch 操作（saveAll）並將 findByAuctionId 的結果預先載入到 Map。 | AuctionResultListener.java:66-94 |
| O-2 | **major** | AuctionStatusService.getAuctionHistory() 使用 `findByStatusOrderByCreatedAtDesc("CLEARED")` 查詢所有已清算 session，然後用 Java stream `.limit(limit)` 截取。這是全表掃描後在記憶體做分頁。應在 Repository 層使用 `Pageable` 參數限制查詢數量。 | AuctionStatusService.java:94-101 |
| O-3 | minor | PlaceAuctionBidService.submitBid() 的步驟順序存在一致性風險：先 forward 到 matchEngine (step 3)，再 persist 到 DB (step 4)，最後 publish event (step 5)。若 step 4 失敗（DB error），bid 已存在於 Redis 但本地無記錄，且 wallet lock event 未發出。建議使用 @Transactional + outbox 模式，或至少在 step 4 失敗時記錄 compensation log。 | PlaceAuctionBidService.java:58-113 |
| O-4 | minor | AuctionResultListener.handleAuctionCleared() 的 bid status 更新邏輯不區分 PARTIAL：`clearedAmount > 0 && clearedAmount >= bidAmount` 判定為 CLEARED，`clearedAmount > 0` 判定為 PARTIAL。但 bidAmount 是 original bid 的 total amount 跨所有 steps，而 clearedAmount 可能恰好等於 bidAmount。邏輯正確但條件判斷順序建議加 comment 釐清。 | AuctionResultListener.java:85-91 |
| O-5 | suggestion | AuctionResultRes.java 已定義但未在任何 Controller 中使用。目前 Controller 直接回傳 AuctionResultDto（common DTO）。若不需要 module-specific response DTO，可移除。 | AuctionResultRes.java |

---

### eap-wallet

**變更檔案**（4 files）：
- AuctionBidListener.java, AuctionSettlementListener.java
- RabbitMQConfig.java (modified)

**問題**：

| # | Severity | 說明 | 檔案 |
|---|----------|------|------|
| W-1 | **major** | AuctionBidListener.onAuctionBidSubmitted() 在餘額不足時僅 log warning 並 return，不拋例外也不發送失敗事件。這意味著 bid 已在 Redis 中被收集（matchEngine 已接受），但 wallet 未鎖定資金。清算時該用戶可能成交但實際無足夠資金。應發送 AuctionBidFailedEvent 通知 matchEngine 移除該 bid，或在 bid 提交流程中先做餘額預檢。 | AuctionBidListener.java:47-51, 54-58 |
| W-2 | **major** | AuctionSettlementListener.handleAuctionCleared() 在 @Transactional 中逐筆處理結果，但對每筆 result 使用 try-catch 繼續處理下一筆。這意味著若第 3 筆失敗，前 2 筆已 commit（@Transactional 在方法結束時 commit），而第 3 筆之後的用戶處理正常。但實際上 @Transactional 會在方法結束時一次 commit 所有變更。如果中間某筆 walletRepository.save() 拋出 exception 被 catch，之後的 save 仍會在同一 transaction 中。嚴格來說不會有部分 commit 問題，但 catch 中的 log.error 可能誤導，因為即使 catch 了 exception，transaction 不一定 rollback（取決於 exception 類型）。建議改用逐筆獨立 transaction（@Transactional(propagation=REQUIRES_NEW)）或 batch 處理。 | AuctionSettlementListener.java:49-89 |
| W-3 | minor | AuctionBidListener 和 AuctionSettlementListener 的 wallet 操作未使用樂觀鎖（@Version）或悲觀鎖（SELECT FOR UPDATE）。在高並發下（同一用戶同時有 CDA 和 auction 操作），可能出現 lost update。建議至少加 @Lock(PESSIMISTIC_WRITE) 或使用 @Version。 | AuctionBidListener.java:38, AuctionSettlementListener.java:65 |

---

### eap-mcp

**變更檔案**（2 files）：
- AuctionMcpTool.java (new)
- OrderServiceClient.java (modified)

**問題**：

| # | Severity | 說明 | 檔案 |
|---|----------|------|------|
| P-1 | minor | AuctionMcpTool.submitAuctionBid() 建構 AuctionBidRequest 時未設定 auctionId。由於 eap-order PlaceAuctionBidService 的驗證要求 auctionId 不為空，MCP tool 提交的 bid 會被擋住。應自動取得 current auctionId 或允許用戶傳入。 | AuctionMcpTool.java:49-53 |

---

## NFR Checklist

### 效能
- [x] **N+1 Query** — **發現問題**：AuctionResultListener.handleAuctionCleared() 每個 result 都做 findByAuctionIdAndUserId + save，形成 N+1 模式（O-1）
- [x] **不必要的全表掃描** — **發現問題**：AuctionStatusService.getAuctionHistory() 載入所有 CLEARED session 後 Java 層 limit（O-2）
- [x] **同步阻塞呼叫** — 通過。PlaceAuctionBidService 對 matchEngine 的 Feign 呼叫是同步的，但因為 bid 提交是用戶發起的一次性操作（非批次），同步 Feign 在此場景可接受。

### 安全性
- [x] **SQL Injection** — 通過。使用 JPA/Hibernate parameterized queries，無原生 SQL 字串拼接。
- [x] **未驗證的使用者輸入** — 通過。AuctionBidRequest.isValid() 驗證 price >= 0, amount > 0。PlaceAuctionBidReq 使用 @NotNull。matchEngine AuctionController 有 config validation。
- [x] **敏感資料外露** — 通過。Log 中未記錄 bid 內容（密封），僅記錄 auctionId、userId、side。
- [x] **權限檢查遺漏** — **發現問題**：PUT /v1/auction/config 無 admin 權限驗證（M-4）

### 可維護性
- [x] **符合 patterns.lock 慣例** — 通過（.blackboard/modules/ 尚未建立 patterns.lock，但程式碼遵循現有 codebase 的 controller-service-repository 分層、@Autowired field injection、ResponseEntity wrapping 等慣例）
- [x] **程式碼結構清晰** — 通過。AuctionClearingService 作為 pure function 設計良好，易於測試。各 module 職責分明。
- [x] **無不必要的複雜度** — 通過。Clearing algorithm 的實作直觀，TreeMap + cumulative curve 的方式符合標準電力市場清算做法。

---

## 跨 Module 一致性檢查

### Event Schema 一致性
| Event | Publisher | Consumer(s) | Schema 一致 |
|-------|-----------|-------------|------------|
| AuctionCreatedEvent | eap-matchEngine | eap-order | OK |
| AuctionBidSubmittedEvent | eap-order | eap-wallet, (eap-matchEngine queue exists but unused by listener) | OK（注：matchEngine 有 queue 但無 listener 消費） |
| AuctionClearedEvent | eap-matchEngine | eap-order, eap-wallet | OK |

### RabbitMQ Routing 一致性
| Exchange | Routing Key | Publisher | Queue(s) | Binding 正確 |
|----------|-------------|-----------|----------|-------------|
| auction.exchange | auction.created | matchEngine | order.auctionCreated.queue | OK |
| auction.exchange | auction.bid.submitted | eap-order | wallet.auctionBidSubmitted.queue, matchEngine.auctionBidSubmitted.queue | OK（matchEngine queue 已綁定但無 listener） |
| auction.exchange | auction.cleared | matchEngine | order.auctionCleared.queue, wallet.auctionCleared.queue | OK |

### Feign 介面匹配
| Feign Client | Method | matchEngine Endpoint | 匹配 |
|-------------|--------|---------------------|------|
| EapMatchEngine | POST /v1/auction/bid | AuctionController POST bid | OK |
| EapMatchEngine | GET /v1/auction/status | AuctionController GET status | OK |
| EapMatchEngine | GET /v1/auction/config | AuctionController GET config | OK |
| EapMatchEngine | PUT /v1/auction/config | AuctionController PUT config | OK |
| OrderServiceClient (MCP) | POST /mcp/v1/auction/bid | McpApiController POST /auction/bid | OK |
| OrderServiceClient (MCP) | GET /mcp/v1/auction/status | McpApiController GET /auction/status | OK |
| OrderServiceClient (MCP) | GET /mcp/v1/auction/results | McpApiController GET /auction/results | OK |

### 注意事項
- matchEngine RabbitMQConfig 綁定了 `matchEngine.auctionBidSubmitted.queue`，但沒有任何 @RabbitListener 消費此 queue。按設計（D3），bid 透過 REST API 直接收集到 Redis，此 queue 似為預留。不影響功能但會導致 message 堆積在 dead letter queue。建議移除或加 listener。

---

## 問題摘要

### Critical (0)
無

### Major (6)
| # | Module | 說明 |
|---|--------|------|
| M-2 | eap-matchEngine | 清算後未清理 Redis keys，累積 leak |
| M-3 | eap-matchEngine | closeAndClear error handling 可能導致 FAILED event 覆蓋 CLEARED event |
| O-1 | eap-order | AuctionResultListener N+1 查詢 |
| O-2 | eap-order | getAuctionHistory 全表掃描 |
| W-1 | eap-wallet | 餘額不足時 bid 仍在 Redis 中，清算可能包含無資金 bid |
| W-2 | eap-wallet | Settlement transaction scope 與 error handling 不一致 |

### Minor (7)
| # | Module | 說明 |
|---|--------|------|
| C-1 | eap-common | auctionId 驗證在 matchEngine vs order 不一致 |
| M-4 | eap-matchEngine | PUT /v1/auction/config 無 admin 權限檢查 |
| M-5 | eap-matchEngine | initAuction Redis 操作非原子 |
| O-3 | eap-order | bid 提交流程無 compensation 機制 |
| O-4 | eap-order | bid status PARTIAL 判斷邏輯可讀性 |
| W-3 | eap-wallet | wallet 操作無並發鎖保護 |
| P-1 | eap-mcp | MCP tool submitBid 未設定 auctionId |

### Suggestion (3)
| # | Module | 說明 |
|---|--------|------|
| C-2 | eap-common | AuctionBidResultEvent 未使用 |
| C-3 | eap-common | Message 中英文混用 |
| O-5 | eap-order | AuctionResultRes 未使用 |
| M-6 | eap-matchEngine | auctionEnabled 雙源問題 |

---

## 決策理由

決策為 **changes_requested** 而非 approved，主要基於以下 major 問題：

1. **W-1（資金鎖定失敗無回饋）**：這是業務邏輯正確性問題。當前設計中，bid 被 matchEngine 接受（Redis 收集成功）後，若 wallet 餘額不足導致鎖定失敗，清算演算法仍會將該 bid 納入計算。這意味著最終清算結果可能包含無法實際結算的 bid，導致結算金額不平衡。此問題必須在上線前修復。

2. **M-2（Redis key leak）**：每小時產生一組 Redis keys 永不清理，長期運行會消耗大量記憶體。修復簡單（在 closeAndClear 成功後呼叫 cleanupAuction），但必須處理。

3. **O-1 + O-2（效能問題）**：N+1 查詢和全表掃描在參與者多時會顯著影響效能。作為 MVP 可暫時接受，但因修復成本低，建議一併修正。

**不退回 Phase 1-2 (needs_redesign) 的理由**：整體設計正確，分 module 職責清晰，event flow 合理。所有 major 問題都是實作層級的修正，不需要重新設計。

**不退回 Phase 1-1 (needs_reanalysis) 的理由**：5 個 affected modules 覆蓋完整，無遺漏。

**建議修正優先序**：
1. W-1 → 在 bid 提交流程中加入餘額預檢（eap-order 在 forward 到 matchEngine 前先同步檢查 wallet），或改為先 lock 後 collect
2. M-2 → 在 closeAndClear 成功發佈 event 後呼叫 auctionRedisService.cleanupAuction(auctionId)
3. M-3 → 將 FAILED event 發佈邏輯移入 lock scope 內
4. O-1 → 改用 batch query + saveAll
5. O-2 → Repository 加 Pageable 參數
6. W-2 → 改為逐筆獨立 transaction 或明確 batch commit
7. P-1 → MCP tool 自動取得 current auctionId
