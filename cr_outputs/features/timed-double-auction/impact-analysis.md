# Impact Analysis: Timed Double Auction (REQ-001)

## 需求摘要

每小時啟動一場 10 分鐘密封式集合競價（Sealed-Bid Call Auction），以 Uniform Price (MCP) 清算，處置未來一小時的電力交割。與現有 CDA 並存，用戶自行選擇交易模式。

### 關鍵設計決策

- 固定截止（:10 分整）、階梯式出價、Pro-rata 邊際分配
- 密封出價（僅顯示參與人數）、不可修改/刪除
- 管理員可配置價格上下限、不支援負價格
- 清算演算法：成交量最大化 → 中間價

---

## 受影響 Module 清單

### 1. eap-common（影響程度：高）

**理由**：需新增 Auction 相關的 Event、DTO、Constants，所有其他 module 都依賴此 library。

**影響範圍**：
- 新增 Event classes：`AuctionCreatedEvent`、`AuctionBidSubmittedEvent`、`AuctionClearedEvent`、`AuctionBidResultEvent`
- 新增 DTO classes：`AuctionBidRequest`（含 steps[]）、`AuctionBidResponse`、`AuctionResultDto`、`AuctionStatusDto`、`ClearingResultDto`
- 新增/修改 `RabbitMQConstants`：新增 auction 相關 routing keys 與 queue 名稱
- 新增 Enum：`AuctionStatus`（OPEN, CLOSED, CLEARING, CLEARED, FAILED）、`TradingMode`（CDA, AUCTION）

### 2. eap-matchEngine（影響程度：高）

**理由**：核心撮合邏輯變更。現有 MatchEngine 是即時撮合（CDA），需新增「收集不撮合」模式 + Uniform Price 清算演算法。

**影響範圍**：
- **新增** Auction 清算服務：供需曲線建構、MCP 計算、Pro-rata 分配
- **新增** Auction orderbook 儲存（Redis）：密封出價收集、階梯式出價展開
- **新增** 定時排程：每小時 :00 開啟拍賣、:10 觸發清算
- **新增** Gate closure 控制：截止後拒絕新出價
- **新增** REST API：拍賣狀態查詢、出價提交
- **新增** RabbitMQ 事件發佈：AuctionClearedEvent（批次撮合結果）
- **修改** OrderConfirmedListener：根據 trading mode 分流（CDA → tryMatch / Auction → collectBid）
- **新增** 價格上下限管理（admin configurable）
- **新增** Lua 腳本：原子性收集 auction bid、批次清算時讀取所有出價

### 3. eap-order（影響程度：高）

**理由**：訂單入口，需支援 auction 出價提交、狀態查詢、結果通知。WebSocket 需支援 auction 即時資訊推送。

**影響範圍**：
- **新增** AuctionController：建立/查詢拍賣 session、提交階梯式出價
- **新增** AuctionService：拍賣出價流程（驗證 → 鎖定 → 轉發）
- **修改** WebSocket 推送：拍賣期間推送參與人數、拍賣結果推送
- **新增** RabbitMQ Listener：消費 AuctionClearedEvent → 儲存成交紀錄 + 推送結果
- **新增** DB：auction_sessions table、auction_bids table、auction_results table
- **修改** McpApiController：新增 auction 相關 MCP endpoint
- **修改** DTO 層：新增 auction 相關 request/response

### 4. eap-wallet（影響程度：中）

**理由**：需處理階梯式出價的鎖定金額計算、清算後批次結算（MCP 退差）、未成交解鎖。

**影響範圍**：
- **新增** AuctionBidListener：消費 AuctionBidSubmittedEvent → 階梯式鎖定（sum of step.price × step.amount for BUY / sum of step.amount for SELL）
- **新增** AuctionSettlementListener：消費 AuctionClearedEvent → 批次結算
  - 成交者：以 MCP 結算，退還買方多鎖金額
  - 未成交者：全額解鎖
  - 部分成交（pro-rata）：按比例結算，剩餘解鎖
- **修改** WalletCheckService：支援階梯式出價的餘額驗證邏輯
- 可能需要新的 outbox event type 處理批次結算結果

### 5. eap-mcp（影響程度：中）

**理由**：需暴露 auction 相關 MCP tools 給 AI client 使用。

**影響範圍**：
- **新增** AuctionMcpTool：submitAuctionBid、getAuctionStatus、getAuctionResult、getAuctionHistory
- **修改** OrderServiceClient（Feign）：新增 auction 相關 endpoint 呼叫
- **修改** SimulationService：可能需支援 auction 模式模擬

### 6. eap-ai-client（影響程度：低）

**理由**：AI client 透過 MCP 間接使用，本身程式碼不需修改。eap-mcp 新增 MCP tools 後，AI client 自動可用。

**影響範圍**：
- 無程式碼修改
- MCP tool 自動發現（Spring AI MCP Client 自動載入新 tools）

---

## 建議 Main Module

**eap-matchEngine**

理由：清算演算法是此 feature 的核心邏輯，且 matchEngine 是撮合引擎的主體。`cr_outputs` 放在 eap-matchEngine repo。

---

## 涉及 API Endpoint

### 新增

| Module | Method | Endpoint | 說明 |
|--------|--------|----------|------|
| eap-matchEngine | POST | `/v1/auction/bid` | 提交階梯式出價（經 eap-order 轉發） |
| eap-matchEngine | GET | `/v1/auction/status` | 查詢當前拍賣 session 狀態 |
| eap-matchEngine | GET | `/v1/auction/config` | 查詢拍賣配置（價格上下限等） |
| eap-matchEngine | PUT | `/v1/auction/config` | 管理員設定拍賣參數 |
| eap-order | POST | `/bid/auction` | 用戶提交 auction 出價入口 |
| eap-order | GET | `/bid/auction/status` | 查詢當前拍賣狀態 |
| eap-order | GET | `/bid/auction/results` | 查詢拍賣結果 |
| eap-order | GET | `/bid/auction/history` | 查詢歷史拍賣清算結果 |
| eap-order | POST | `/mcp/v1/auction/bid` | MCP 版 auction 出價 |
| eap-order | GET | `/mcp/v1/auction/status` | MCP 版拍賣狀態 |
| eap-order | GET | `/mcp/v1/auction/results` | MCP 版拍賣結果 |

### 修改

| Module | Endpoint | 異動 |
|--------|----------|------|
| eap-order | WebSocket `/topic/*` | 新增 `/topic/auction/status`、`/topic/auction/result` |

---

## 預計異動檔案清單

### eap-common
- `src/main/java/com/eap/common/event/AuctionCreatedEvent.java`（新增）
- `src/main/java/com/eap/common/event/AuctionBidSubmittedEvent.java`（新增）
- `src/main/java/com/eap/common/event/AuctionClearedEvent.java`（新增）
- `src/main/java/com/eap/common/event/AuctionBidResultEvent.java`（新增）
- `src/main/java/com/eap/common/dto/AuctionBidRequest.java`（新增）
- `src/main/java/com/eap/common/dto/AuctionBidResponse.java`（新增）
- `src/main/java/com/eap/common/dto/AuctionResultDto.java`（新增）
- `src/main/java/com/eap/common/dto/AuctionStatusDto.java`（新增）
- `src/main/java/com/eap/common/dto/ClearingResultDto.java`（新增）
- `src/main/java/com/eap/common/constants/RabbitMQConstants.java`（修改）

### eap-matchEngine
- `src/main/java/com/eap/eap_matchengine/application/AuctionClearingService.java`（新增）
- `src/main/java/com/eap/eap_matchengine/application/AuctionCollectorService.java`（新增）
- `src/main/java/com/eap/eap_matchengine/application/AuctionSchedulerService.java`（新增）
- `src/main/java/com/eap/eap_matchengine/application/AuctionConfigService.java`（新增）
- `src/main/java/com/eap/eap_matchengine/controller/AuctionController.java`（新增）
- `src/main/java/com/eap/eap_matchengine/application/OrderConfirmedListener.java`（修改）
- `src/main/java/com/eap/eap_matchengine/configuration/config/RabbitMQConfig.java`（修改）
- `src/main/resources/lua/collect_auction_bid.lua`（新增）
- `src/main/resources/lua/get_all_auction_bids.lua`（新增）
- `src/main/resources/application.yml`（修改：auction 配置）

### eap-order
- `src/main/java/com/eap/eap_order/controller/AuctionController.java`（新增）
- `src/main/java/com/eap/eap_order/controller/dto/req/PlaceAuctionBidReq.java`（新增）
- `src/main/java/com/eap/eap_order/controller/dto/res/AuctionResultRes.java`（新增）
- `src/main/java/com/eap/eap_order/application/PlaceAuctionBidService.java`（新增）
- `src/main/java/com/eap/eap_order/application/AuctionResultListener.java`（新增）
- `src/main/java/com/eap/eap_order/application/AuctionStatusService.java`（新增）
- `src/main/java/com/eap/eap_order/application/OutBound/EapMatchEngine.java`（修改：新增 auction API）
- `src/main/java/com/eap/eap_order/controller/McpApiController.java`（修改：新增 auction endpoint）
- `src/main/java/com/eap/eap_order/configuration/config/RabbitMQConfig.java`（修改）
- `src/main/java/com/eap/eap_order/configuration/WebSocketConfig.java`（可能修改）
- `src/main/java/com/eap/eap_order/domain/entity/AuctionSessionEntity.java`（新增）
- `src/main/java/com/eap/eap_order/domain/entity/AuctionBidEntity.java`（新增）
- `src/main/java/com/eap/eap_order/domain/entity/AuctionResultEntity.java`（新增）
- `src/main/java/com/eap/eap_order/configuration/repository/AuctionSessionRepository.java`（新增）
- `src/main/java/com/eap/eap_order/configuration/repository/AuctionBidRepository.java`（新增）
- `src/main/java/com/eap/eap_order/configuration/repository/AuctionResultRepository.java`（新增）
- `src/main/resources/db/changelog/db.changelog-master.xml`（修改：引入新 changelog）
- `src/main/resources/db/changelog/db.changelog-auction.xml`（新增）
- `src/main/resources/openapi/orderService.yml`（修改：新增 auction endpoint）

### eap-wallet
- `src/main/java/com/eap/eap_wallet/application/AuctionBidListener.java`（新增）
- `src/main/java/com/eap/eap_wallet/application/AuctionSettlementListener.java`（新增）
- `src/main/java/com/eap/eap_wallet/application/WalletCheckService.java`（修改：階梯式鎖定）
- `src/main/java/com/eap/eap_wallet/configuration/config/RabbitMQConfig.java`（修改）

### eap-mcp
- `src/main/java/com/eap/mcp/tools/mcp/AuctionMcpTool.java`（新增）
- `src/main/java/com/eap/mcp/client/OrderServiceClient.java`（修改：新增 auction Feign method）

---

## Schema 異動

### eap-order（PostgreSQL, schema: order_service）

**新增 table: auction_sessions**

| Column | Type | Constraints | 說明 |
|--------|------|------------|------|
| id | BIGSERIAL | PK | |
| auction_id | VARCHAR(20) | UNIQUE, NOT NULL | 拍賣 ID（如 AUC-2026040910） |
| status | VARCHAR(20) | NOT NULL | OPEN / CLOSED / CLEARING / CLEARED / FAILED |
| delivery_hour | TIMESTAMP | NOT NULL | 交割時段 |
| open_time | TIMESTAMP | NOT NULL | 開始收單時間 |
| close_time | TIMESTAMP | NOT NULL | Gate closure 時間 |
| clearing_price | INTEGER | NULLABLE | MCP（清算後填入） |
| clearing_volume | INTEGER | NULLABLE | MCV（清算後填入） |
| participant_count | INTEGER | DEFAULT 0 | 參與人數 |
| price_floor | INTEGER | NOT NULL | 價格下限 |
| price_ceiling | INTEGER | NOT NULL | 價格上限 |
| created_at | TIMESTAMP | NOT NULL | |

**新增 table: auction_bids**

| Column | Type | Constraints | 說明 |
|--------|------|------------|------|
| id | BIGSERIAL | PK | |
| auction_id | VARCHAR(20) | NOT NULL, FK | |
| user_id | UUID | NOT NULL | |
| side | VARCHAR(4) | NOT NULL | BUY / SELL |
| steps | JSONB | NOT NULL | 階梯式出價 [{price, amount}] |
| total_locked | INTEGER | NOT NULL | 鎖定總額 |
| status | VARCHAR(20) | NOT NULL | SUBMITTED / CLEARED / PARTIAL / NOT_CLEARED |
| created_at | TIMESTAMP | NOT NULL | |
| UNIQUE(auction_id, user_id, side) | | | 同場同用戶同方向只能一筆 |

**新增 table: auction_results**

| Column | Type | Constraints | 說明 |
|--------|------|------------|------|
| id | BIGSERIAL | PK | |
| auction_id | VARCHAR(20) | NOT NULL, FK | |
| user_id | UUID | NOT NULL | |
| side | VARCHAR(4) | NOT NULL | |
| clearing_price | INTEGER | NOT NULL | MCP |
| bid_amount | INTEGER | NOT NULL | 原始申報量 |
| cleared_amount | INTEGER | NOT NULL | 成交量 |
| settlement_amount | INTEGER | NOT NULL | 結算金額（MCP × cleared_amount） |
| created_at | TIMESTAMP | NOT NULL | |

### eap-matchEngine（Redis）

**新增 key patterns**：
- `auction:{auctionId}:config` → Hash（status, open_time, close_time, price_floor, price_ceiling）
- `auction:{auctionId}:bids:buy` → List of JSON（階梯式出價）
- `auction:{auctionId}:bids:sell` → List of JSON
- `auction:{auctionId}:participants` → Set of userId
- `auction:current` → String（當前 active auctionId）
- `auction:config` → Hash（price_floor, price_ceiling, duration_minutes 等全域配置）

---

## Breaking Change 風險

| 風險 | 等級 | 說明 |
|------|------|------|
| RabbitMQConstants 變更 | 低 | 新增常數，不修改現有常數 |
| eap-common 版本 | 低 | 新增 class，不修改現有 class |
| 現有 CDA 流程 | 低 | CDA 保持不變，auction 為獨立流程 |
| OrderConfirmedListener 分流 | 中 | 修改現有 listener 邏輯，需確保 CDA 路徑不受影響 |
| Wallet 鎖定邏輯 | 中 | 新增階梯式鎖定計算，需確保不影響現有單點鎖定 |

整體 breaking change 風險**低**。Auction 為新增功能，主要是 additive change，不修改現有 CDA 核心路徑。唯一需注意的是 OrderConfirmedListener 的分流邏輯和 Wallet 鎖定邏輯的變更。

---

## 併發風險

目前無其他 active feature（這是第一個需求），無併發風險。
