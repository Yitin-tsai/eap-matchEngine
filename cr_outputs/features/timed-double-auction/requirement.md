# Requirement: Timed Double Auction (REQ-001)

## Raw Input

每小時啟動一場 10 分鐘密封式集合競價（Sealed-Bid Call Auction），以 Uniform Price (MCP) 清算，處置未來一小時的電力交割。與現有連續雙邊拍賣（CDA）並存，用戶自行選擇交易模式。

## Phase 0 Investigation Summary

### Timeline
```
:00 --- Auction opens (collect bids for next hour delivery)
:10 --- Gate Closure (fixed cutoff, no random offset)
:10~:11 - System clearing (calculate MCP)
:11 --- Results published + wallet settlement
:00 (next hour) --- Power delivery
```

### Confirmed Design Decisions

| # | Item | Decision |
|---|------|----------|
| 1 | Cutoff time | Fixed :10, no random cutoff |
| 2 | Pricing rule | Maximize clearing volume -> take midpoint if multiple solutions |
| 3 | Marginal order allocation | Pro-rata proportional allocation |
| 4 | Bid format | Stepwise bid curve (steps[]) |
| 5 | Negative prices | Not supported (price >= 0) |
| 6 | Price limits | Admin-configurable (not hardcoded) |
| 7 | Visible info during auction | Only participant count |
| 8 | Relationship with CDA | Coexist, user chooses trading mode |

### Clearing Algorithm

1. Collect all buy orders (sort by price descending -> demand curve)
2. Collect all sell orders (sort by price ascending -> supply curve)
3. Find supply-demand intersection -> MCP (Market Clearing Price) + MCV (Market Clearing Volume)
4. All cleared participants settle at uniform MCP
5. Edge cases: no intersection -> no trade; multiple solutions -> maximize volume then midpoint; marginal orders -> pro-rata

### Bid Format (Stepwise)

```json
{
  "userId": "uuid",
  "side": "BUY",
  "auctionId": "AUC-2026040910",
  "steps": [
    { "price": 60, "amount": 50 },
    { "price": 55, "amount": 30 },
    { "price": 50, "amount": 20 }
  ]
}
```

- Bids cannot be modified or deleted after submission
- Sealed: other bids not visible before cutoff, only participant count shown

### Settlement Flow

- Buyer: lock = sum(step.price * step.amount); settle at MCP, refund difference
- Seller: lock = sum(step.amount); settle at MCP
- Uncleared portion: full unlock
- Partial clearing (marginal orders): pro-rata allocation, remainder unlocked

## Confirmed Scope

### Affected Modules

| Module | Impact | Description |
|--------|--------|-------------|
| eap-common | High | New events, DTOs, constants, enums |
| eap-matchEngine | High | Core clearing algorithm, Redis bid collection, scheduling |
| eap-order | High | Bid entry, DB persistence, WebSocket, MCP endpoints |
| eap-wallet | Medium | Stepwise fund locking, batch settlement |
| eap-mcp | Medium | MCP tools for AI client |

### Main Module

**eap-matchEngine** - clearing algorithm is the core logic of this feature.

### New API Endpoints

| Module | Method | Endpoint | Description |
|--------|--------|----------|-------------|
| eap-matchEngine | POST | /v1/auction/bid | Submit stepwise bid |
| eap-matchEngine | GET | /v1/auction/status | Query current auction status |
| eap-matchEngine | GET | /v1/auction/config | Query auction config |
| eap-matchEngine | PUT | /v1/auction/config | Admin set auction parameters |
| eap-order | POST | /bid/auction | User bid entry point |
| eap-order | GET | /bid/auction/status | Query auction status |
| eap-order | GET | /bid/auction/results | Query clearing results |
| eap-order | GET | /bid/auction/history | Query historical results |
| eap-order | POST | /mcp/v1/auction/bid | MCP bid entry |
| eap-order | GET | /mcp/v1/auction/status | MCP auction status |
| eap-order | GET | /mcp/v1/auction/results | MCP auction results |

### Schema Changes

- eap-order: 3 new tables (auction_sessions, auction_bids, auction_results)
- eap-matchEngine: New Redis key patterns for auction bid collection

### Breaking Change Risk

Low. Auction is a new feature with additive changes. CDA flow remains unchanged.
