# Requirement: auction-risk-fixes

## 原始需求

修復 timed-double-auction 整合測試（CONDITIONAL_PASS）遺留的 5 項風險，使系統達到正式可用狀態。

## 確認後範圍

### 受影響 Module

| Module | 影響程度 | 主要變更 |
|--------|----------|----------|
| eap-matchEngine | minor | RISK-3: 補宣告 DLX bean; RISK-6: auctionEnabled 改讀 Redis |
| eap-wallet | major | RISK-3: 補宣告 DLX bean; RISK-4: 新增冪等保護 table + guard; W-3: 樂觀鎖 retry |
| eap-order | minor | RISK-5: 修改 auction_bids UNIQUE constraint |

### 涉及 API Endpoint

| Module | Endpoint | 異動類型 |
|--------|----------|----------|
| eap-matchEngine | POST /v1/auction/bid | 行為不變（Lua 已攔截，DB constraint 對齊語意） |
| eap-matchEngine | PUT /v1/auction/config | 行為修復（auctionEnabled 現在即時生效） |

無新增 API，無 response schema 變更。

### Schema 異動

| Module | Table | 異動內容 |
|--------|-------|----------|
| eap-order | auction_bids | UNIQUE constraint 從 (auction_id, user_id, side) 改為 (auction_id, user_id) |
| eap-wallet | settlement_idempotency | 新增 table，用於 AuctionSettlementListener 冪等保護 |

## 業務規則

- 不允許自我對沖：同一用戶在同一 auction 只能有一筆 bid（不分 BUY/SELL side）
- 動態開關：PUT /v1/auction/config 修改 auctionEnabled 後，scheduler 應即時讀取 Redis 中的值
- 冪等結算：同一 (auctionId, userId, side) 的結算只執行一次，重複 message 自動 skip
- 樂觀鎖重試：wallet 寫入遇到 OptimisticLockException 時最多重試 3 次
