# Implementation Plan: Timed Double Auction

## Task Execution Order

```
Phase 1: eap-common (DEV-001 ~ DEV-004) - shared library, all modules depend on it
  DEV-001: Enums (AuctionStatus, TradingMode)
  DEV-002: RabbitMQ constants
  DEV-003: Event classes (depends on DEV-001)
  DEV-004: DTO classes (depends on DEV-001)

Phase 2: Parallel across modules (after eap-common is built/published)
  eap-matchEngine: DEV-005 ~ DEV-011
  eap-order: DEV-012 ~ DEV-018
  eap-wallet: DEV-019 ~ DEV-021
  eap-mcp: DEV-022 ~ DEV-023

Within eap-matchEngine:
  DEV-005 (RabbitMQ config) -> DEV-006 (Lua scripts) -> DEV-007 (AuctionRedisService)
  -> DEV-008 (AuctionClearingService) -> DEV-009 (AuctionSchedulerService)
  DEV-010 (AuctionController, depends on DEV-007)
  DEV-011 (OrderConfirmedListener minor change)

Within eap-order:
  DEV-012 (DB schema) -> DEV-013 (entities/repos) -> DEV-015 (PlaceAuctionBidService)
  DEV-014 (RabbitMQ config)
  DEV-016 (AuctionResultListener, depends on DEV-013, DEV-014)
  DEV-017 (AuctionController, depends on DEV-015, DEV-013)
  DEV-018 (Feign + MCP endpoints, depends on DEV-010, DEV-017)

Within eap-wallet:
  DEV-019 (RabbitMQ config) -> DEV-020 (AuctionBidListener) + DEV-021 (AuctionSettlementListener)

Within eap-mcp:
  DEV-022 (Feign methods, depends on DEV-018) -> DEV-023 (AuctionMcpTool)
```

---

## Module: eap-common

### DEV-001: New file `src/main/java/com/eap/common/constants/AuctionStatus.java`

```pseudo
enum AuctionStatus {
  OPEN("Auction is open for bidding"),
  CLOSED("Gate closure, no more bids accepted"),
  CLEARING("Clearing in progress"),
  CLEARED("Clearing complete, results available"),
  FAILED("Clearing failed, no match found");

  private final String description;
  // constructor, getter
}
```

### DEV-001: New file `src/main/java/com/eap/common/constants/TradingMode.java`

```pseudo
enum TradingMode {
  CDA("Continuous Double Auction"),
  AUCTION("Timed Sealed-Bid Call Auction");

  private final String description;
}
```

### DEV-002: Modify `src/main/java/com/eap/common/constants/RabbitMQConstants.java`

```pseudo
// Add after existing constants (before deprecated section):

// Auction Exchange
AUCTION_EXCHANGE = "auction.exchange"

// Auction Routing Keys
AUCTION_BID_SUBMITTED_KEY = "auction.bid.submitted"
AUCTION_CLEARED_KEY = "auction.cleared"
AUCTION_CREATED_KEY = "auction.created"
AUCTION_BID_RESULT_KEY = "auction.bid.result"

// Wallet Module Auction Queues
WALLET_AUCTION_BID_SUBMITTED_QUEUE = "wallet.auctionBidSubmitted.queue"
WALLET_AUCTION_CLEARED_QUEUE = "wallet.auctionCleared.queue"

// Order Module Auction Queues
ORDER_AUCTION_CLEARED_QUEUE = "order.auctionCleared.queue"
ORDER_AUCTION_CREATED_QUEUE = "order.auctionCreated.queue"

// MatchEngine Module Auction Queues
MATCH_ENGINE_AUCTION_BID_SUBMITTED_QUEUE = "matchEngine.auctionBidSubmitted.queue"
```

### DEV-003: New file `src/main/java/com/eap/common/event/AuctionCreatedEvent.java`

```pseudo
@Data @NoArgsConstructor @AllArgsConstructor @Builder
class AuctionCreatedEvent {
  String auctionId       // e.g., "AUC-2026040910"
  LocalDateTime deliveryHour
  LocalDateTime openTime
  LocalDateTime closeTime
  Integer priceFloor
  Integer priceCeiling
  LocalDateTime createdAt
}
```

### DEV-003: New file `src/main/java/com/eap/common/event/AuctionBidSubmittedEvent.java`

```pseudo
@Data @NoArgsConstructor @AllArgsConstructor @Builder
class AuctionBidSubmittedEvent {
  String auctionId
  UUID userId
  String side            // "BUY" or "SELL"
  List<BidStep> steps
  Integer totalLocked    // pre-calculated: BUY=sum(price*amount), SELL=sum(amount)
  LocalDateTime createdAt

  @Data @NoArgsConstructor @AllArgsConstructor
  static class BidStep {
    Integer price
    Integer amount
  }
}
```

### DEV-003: New file `src/main/java/com/eap/common/event/AuctionClearedEvent.java`

```pseudo
@Data @NoArgsConstructor @AllArgsConstructor @Builder
class AuctionClearedEvent {
  String auctionId
  Integer clearingPrice
  Integer clearingVolume
  String status          // "CLEARED" or "FAILED"
  List<AuctionBidResult> results
  LocalDateTime clearedAt

  @Data @NoArgsConstructor @AllArgsConstructor
  static class AuctionBidResult {
    UUID userId
    String side
    Integer bidAmount          // total amount across all steps
    Integer clearedAmount      // allocated amount
    Integer settlementAmount   // clearedAmount * MCP
    Integer originalTotalLocked // for wallet refund calculation
  }
}
```

### DEV-003: New file `src/main/java/com/eap/common/event/AuctionBidResultEvent.java`

```pseudo
@Data @NoArgsConstructor @AllArgsConstructor @Builder
class AuctionBidResultEvent {
  String auctionId
  UUID userId
  String side
  Integer clearingPrice
  Integer bidAmount
  Integer clearedAmount
  Integer settlementAmount
  String status          // "CLEARED", "PARTIAL", "NOT_CLEARED"
  LocalDateTime clearedAt
}
```

### DEV-004: New DTO files (6 files in `src/main/java/com/eap/common/dto/`)

**AuctionBidRequest.java**:
```pseudo
@Data @NoArgsConstructor @AllArgsConstructor @Builder
class AuctionBidRequest {
  String userId, auctionId, side
  List<BidStep> steps  // inner class: {price, amount}

  boolean isValid() {
    return userId != blank && auctionId != blank
        && side in ("BUY","SELL") && steps not empty
        && all steps have price >= 0 and amount > 0
  }
  String getValidationError() { ... }
}
```

**AuctionBidResponse.java**:
```pseudo
@Data @NoArgsConstructor @AllArgsConstructor
class AuctionBidResponse {
  String auctionId, userId, side
  Integer totalLocked
  boolean success
  String message
  LocalDateTime submittedAt

  static success(auctionId, userId, side, totalLocked) { ... }
  static failure(message) { ... }
}
```

**AuctionStatusDto.java**:
```pseudo
@Data @NoArgsConstructor @AllArgsConstructor @Builder
class AuctionStatusDto {
  String auctionId, status
  LocalDateTime deliveryHour, openTime, closeTime
  Integer participantCount, clearingPrice, clearingVolume
  Integer priceFloor, priceCeiling
}
```

**AuctionResultDto.java**:
```pseudo
@Data @NoArgsConstructor @AllArgsConstructor @Builder
class AuctionResultDto {
  String auctionId
  Integer clearingPrice, clearingVolume, participantCount
  List<UserResult> results  // inner: userId, side, bidAmount, clearedAmount, settlementAmount, status
  LocalDateTime clearedAt
}
```

**ClearingResultDto.java**:
```pseudo
@Data @NoArgsConstructor @AllArgsConstructor @Builder
class ClearingResultDto {
  String auctionId
  Integer clearingPrice, clearingVolume
  Integer totalBuyVolume, totalSellVolume, matchedPairs
  String status
}
```

**AuctionConfigDto.java**:
```pseudo
@Data @NoArgsConstructor @AllArgsConstructor @Builder
class AuctionConfigDto {
  Integer priceFloor, priceCeiling, durationMinutes
  boolean auctionEnabled
}
```

---

## Module: eap-matchEngine

### DEV-005: Modify `src/main/java/.../configuration/config/RabbitMQConfig.java`

```pseudo
// Add:
@Bean TopicExchange auctionExchange() { new TopicExchange(AUCTION_EXCHANGE) }
@Bean Queue matchEngineAuctionBidSubmittedQueue() {
  QueueBuilder.durable(MATCH_ENGINE_AUCTION_BID_SUBMITTED_QUEUE).withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE).build()
}
@Bean Binding matchEngineAuctionBidSubmittedBinding(...) {
  bind queue to auctionExchange with AUCTION_BID_SUBMITTED_KEY
}
```

### DEV-005: Modify `src/main/resources/application.yml`

```yaml
# Add:
auction:
  enabled: true
  duration-minutes: 10
  default-price-floor: 0
  default-price-ceiling: 9999
```

### DEV-006: New file `src/main/resources/lua/collect_auction_bid.lua`

```pseudo
-- KEYS[1] = auction:{id}:bids:{side}, KEYS[2] = auction:{id}:participants, KEYS[3] = auction:{id}:config
-- ARGV[1] = userId, ARGV[2] = bidJson
local status = redis.call('HGET', KEYS[3], 'status')
if status ~= 'OPEN' then return -1 end                    -- gate closed
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -2 end  -- duplicate
redis.call('RPUSH', KEYS[1], ARGV[2])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
```

### DEV-006: New file `src/main/resources/lua/get_all_auction_bids.lua`

```pseudo
-- KEYS[1] = auction:{id}:bids:buy, KEYS[2] = auction:{id}:bids:sell
local buys = redis.call('LRANGE', KEYS[1], 0, -1)
local sells = redis.call('LRANGE', KEYS[2], 0, -1)
return {buys, sells}
```

### DEV-007: New file `src/main/java/.../application/AuctionRedisService.java`

```pseudo
@Service @Slf4j
class AuctionRedisService {
  final RedisTemplate<String,String> redisTemplate
  final ObjectMapper objectMapper
  String collectBidLua, getAllBidsLua  // loaded at @PostConstruct

  initAuction(auctionId, config):
    HSET auction:{auctionId}:config -> status=OPEN, open_time, close_time, price_floor, price_ceiling
    SET auction:current -> auctionId

  collectBid(auctionId, side, userId, bidJson) -> int:
    execute collectBidLua with keys=[bids:{side}, participants, config], args=[userId, bidJson]
    return result code (1=ok, -1=closed, -2=dup)

  closeGate(auctionId):
    HSET auction:{auctionId}:config status CLOSED

  getAllBids(auctionId) -> {buyBids, sellBids}:
    execute getAllBidsLua, parse JSON arrays

  getParticipantCount(auctionId) -> int:
    SCARD auction:{auctionId}:participants

  getCurrentAuctionId() -> String:
    GET auction:current

  getAuctionConfig(auctionId) -> Map:
    HGETALL auction:{auctionId}:config

  saveGlobalConfig(config) / getGlobalConfig():
    HSET/HGETALL auction:config
}
```

### DEV-008: New file `src/main/java/.../application/AuctionClearingService.java`

```pseudo
@Service @Slf4j
class AuctionClearingService {

  ClearingResult clear(List<AuctionBidSubmittedEvent> buyBids, List<AuctionBidSubmittedEvent> sellBids):
    if buyBids.empty || sellBids.empty -> return FAILED("No bids on one side")

    // Step 1: Expand stepwise bids to flat (price, qty, userId, side) entries
    List<FlatBid> allBuys = expandBids(buyBids)
    List<FlatBid> allSells = expandBids(sellBids)

    // Step 2: Build aggregate demand curve (buy: sort price DESC, cumulate qty)
    sort allBuys by price DESC
    demandCurve = buildCumulativeCurve(allBuys)

    // Step 3: Build aggregate supply curve (sell: sort price ASC, cumulate qty)
    sort allSells by price ASC
    supplyCurve = buildCumulativeCurve(allSells)

    // Step 4: Find MCP & MCV
    //   Iterate price levels. At each price p:
    //     demand(p) = total buy qty with price >= p
    //     supply(p) = total sell qty with price <= p
    //     volume(p) = min(demand(p), supply(p))
    //   Find price(s) that maximize volume(p).
    //   If max volume == 0 -> FAILED
    //   If multiple prices -> midpoint

    // Step 5: Allocate to individual bids
    //   Buyers with bid price > MCP: fully cleared (up to their total qty)
    //   Buyers at bid price == MCP: marginal, pro-rata share of remaining buy volume
    //   Sellers with bid price < MCP: fully cleared
    //   Sellers at bid price == MCP: marginal, pro-rata share of remaining sell volume

    // Step 6: Build per-user results
    //   Aggregate back to user level (sum cleared amounts across steps)
    //   settlementAmount = clearedAmount * MCP

    return ClearingResult(clearingPrice=MCP, clearingVolume=MCV, status="CLEARED", results=[...])

  // Helper: pro-rata allocation
  proRata(List<{userId, qty}> marginals, int available):
    total = sum of all qty
    for each: allocated = floor(qty / total * available)
    remainder = available - sum(allocated)
    distribute remainder one-by-one by original insertion order
}
```

### DEV-009: New file `src/main/java/.../application/AuctionSchedulerService.java`

```pseudo
@Service @Slf4j
class AuctionSchedulerService {
  final AuctionRedisService auctionRedisService
  final AuctionClearingService clearingService
  final RabbitTemplate rabbitTemplate
  final RedissonClient redissonClient

  @Value("${auction.enabled:true}") boolean auctionEnabled

  @Scheduled(cron = "0 0 * * * *")  // every hour at :00
  openAuction():
    if !auctionEnabled -> return
    acquire lock "auction:scheduler:open" (5s wait, 30s lease)
    auctionId = "AUC-" + format(delivery hour = current hour + 1)
    config = auctionRedisService.getGlobalConfig()
    auctionRedisService.initAuction(auctionId, config)
    publish AuctionCreatedEvent to AUCTION_EXCHANGE / AUCTION_CREATED_KEY
    release lock

  @Scheduled(cron = "0 10 * * * *")  // every hour at :10
  closeAndClear():
    if !auctionEnabled -> return
    acquire lock "auction:scheduler:clear" (5s wait, 60s lease)
    auctionId = auctionRedisService.getCurrentAuctionId()
    if null -> return

    auctionRedisService.closeGate(auctionId)
    {buyBids, sellBids} = auctionRedisService.getAllBids(auctionId)
    result = clearingService.clear(buyBids, sellBids)

    event = AuctionClearedEvent.builder()
      .auctionId(auctionId)
      .clearingPrice(result.clearingPrice)
      .clearingVolume(result.clearingVolume)
      .status(result.status)
      .results(result.results)
      .clearedAt(now)
      .build()
    publish event to AUCTION_EXCHANGE / AUCTION_CLEARED_KEY
    release lock
```

### DEV-010: New file `src/main/java/.../controller/AuctionController.java`

```pseudo
@RestController @RequestMapping("v1/auction")
class AuctionController {
  @Autowired AuctionRedisService auctionRedisService

  POST /bid (AuctionBidRequest body):
    validate body.isValid()
    auctionId = body.auctionId ?: auctionRedisService.getCurrentAuctionId()
    bidJson = objectMapper.writeValueAsString(body)
    result = auctionRedisService.collectBid(auctionId, body.side, body.userId, bidJson)
    if result == 1 -> return ok(AuctionBidResponse.success(...))
    if result == -1 -> return badRequest("Auction gate is closed")
    if result == -2 -> return badRequest("Duplicate bid")

  GET /status:
    auctionId = auctionRedisService.getCurrentAuctionId()
    if null -> return ok(AuctionStatusDto with status="NO_ACTIVE_AUCTION")
    config = auctionRedisService.getAuctionConfig(auctionId)
    count = auctionRedisService.getParticipantCount(auctionId)
    return ok(build AuctionStatusDto)

  GET /config:
    return ok(auctionRedisService.getGlobalConfig())

  PUT /config (AuctionConfigDto body):
    validate priceFloor >= 0, priceCeiling > priceFloor
    auctionRedisService.saveGlobalConfig(body)
    return ok(body)
```

### DEV-011: Modify `src/main/java/.../application/OrderConfirmedListener.java`

```pseudo
// Minimal change:
// Replace System.out.println with log.info
// Add comment: "CDA mode only - auction bids go through AuctionController directly"
// No functional change
```

---

## Module: eap-order

### DEV-012: New file `src/main/resources/db/changelog/db.changelog-auction.xml`

```xml
<!-- changeSet auction-001: CREATE TABLE auction_sessions -->
<!-- changeSet auction-002: CREATE TABLE auction_bids (FK to auction_sessions) -->
<!-- changeSet auction-003: CREATE TABLE auction_results (FK to auction_sessions) -->
<!-- changeSet auction-004: CREATE INDEX on auction_bids, auction_results, auction_sessions -->
```

Full DDL in impact-analysis.md#schema-changes.

### DEV-012: Modify `src/main/resources/db/changelog/db.changelog-master.xml`

```xml
<!-- Add at end: -->
<include file="db/changelog/db.changelog-auction.xml"/>
```

### DEV-013: New entity and repository files (6 files)

**AuctionSessionEntity.java**: Maps to `auction_sessions` table. Fields per schema.
**AuctionBidEntity.java**: Maps to `auction_bids` table. Steps stored as JSON string.
**AuctionResultEntity.java**: Maps to `auction_results` table.
**AuctionSessionRepository.java**: findByAuctionId, findByStatusOrderByCreatedAtDesc.
**AuctionBidRepository.java**: findByAuctionId, findByAuctionIdAndUserId, existsByAuctionIdAndUserIdAndSide.
**AuctionResultRepository.java**: findByAuctionId, findByAuctionIdAndUserId, findByUserId.

### DEV-014: Modify `src/main/java/.../configuration/config/RabbitMQConfig.java`

```pseudo
// Add beans:
auctionExchange() -> TopicExchange(AUCTION_EXCHANGE)
orderAuctionClearedQueue() -> durable with DLX
orderAuctionCreatedQueue() -> durable with DLX
orderAuctionClearedBinding() -> bind to AUCTION_CLEARED_KEY
orderAuctionCreatedBinding() -> bind to AUCTION_CREATED_KEY
```

### DEV-015: New file `src/main/java/.../application/PlaceAuctionBidService.java`

```pseudo
@Service @Slf4j
class PlaceAuctionBidService {
  @Autowired RabbitTemplate rabbitTemplate
  @Autowired AuctionBidRepository bidRepository
  @Autowired EapMatchEngine eapMatchEngine

  AuctionBidResponse submitBid(AuctionBidRequest request):
    // 1. Validate
    if !request.isValid() -> return failure(request.getValidationError())

    // 2. Calculate totalLocked
    totalLocked = if BUY: sum(step.price * step.amount) else: sum(step.amount)

    // 3. Forward to matchEngine for Redis collection
    response = eapMatchEngine.submitAuctionBid(request)
    if !response.success -> return failure

    // 4. Persist to local DB
    entity = AuctionBidEntity(auctionId, userId, side, steps=JSON, totalLocked, "SUBMITTED")
    bidRepository.save(entity)

    // 5. Publish event for wallet locking
    event = AuctionBidSubmittedEvent(auctionId, userId, side, steps, totalLocked, now)
    rabbitTemplate.convertAndSend(AUCTION_EXCHANGE, AUCTION_BID_SUBMITTED_KEY, event)

    return AuctionBidResponse.success(auctionId, userId, side, totalLocked)
```

### DEV-016: New file `src/main/java/.../application/AuctionResultListener.java`

```pseudo
@Component @Slf4j
class AuctionResultListener {
  @Autowired AuctionSessionRepository sessionRepo
  @Autowired AuctionBidRepository bidRepo
  @Autowired AuctionResultRepository resultRepo
  @Autowired SimpMessagingTemplate messagingTemplate

  @RabbitListener(queues = ORDER_AUCTION_CLEARED_QUEUE) @Transactional
  handleAuctionCleared(AuctionClearedEvent event):
    session = sessionRepo.findByAuctionId(event.auctionId)
    session.clearingPrice = event.clearingPrice
    session.clearingVolume = event.clearingVolume
    session.status = event.status
    sessionRepo.save(session)

    for result in event.results:
      resultRepo.save(AuctionResultEntity from result)
      bidRepo.findByAuctionIdAndUserId(event.auctionId, result.userId)
        .forEach(bid -> bid.status = result.clearedAmount > 0 ? "CLEARED" : "NOT_CLEARED")

    messagingTemplate.convertAndSend("/topic/auction/result", buildResultDto(event))

  @RabbitListener(queues = ORDER_AUCTION_CREATED_QUEUE)
  handleAuctionCreated(AuctionCreatedEvent event):
    session = new AuctionSessionEntity(from event fields, status=OPEN)
    sessionRepo.save(session)
    messagingTemplate.convertAndSend("/topic/auction/status", buildStatusDto(session))
```

### DEV-017: New files (4 files)

**PlaceAuctionBidReq.java**:
```pseudo
@Data @Builder
class PlaceAuctionBidReq {
  @NotNull UUID userId
  @NotNull String auctionId
  @NotNull String side
  @NotNull List<Step> steps
  @Data static class Step { @NotNull Integer price; @NotNull Integer amount; }
}
```

**AuctionResultRes.java**:
```pseudo
@Data @Builder
class AuctionResultRes {
  String auctionId
  Integer clearingPrice, clearingVolume, participantCount
  List<UserResult> results
  LocalDateTime clearedAt
}
```

**AuctionStatusService.java**:
```pseudo
@Service
class AuctionStatusService {
  @Autowired AuctionSessionRepository sessionRepo
  @Autowired AuctionResultRepository resultRepo

  getCurrentAuctionStatus() -> AuctionStatusDto:
    session = sessionRepo.findFirstByStatusOrderByCreatedAtDesc("OPEN")
    if null -> return "NO_ACTIVE_AUCTION"
    build AuctionStatusDto from session

  getAuctionResults(auctionId) -> AuctionResultDto:
    session = sessionRepo.findByAuctionId(auctionId)
    results = resultRepo.findByAuctionId(auctionId)
    build AuctionResultDto

  getAuctionHistory(limit) -> List<AuctionStatusDto>:
    sessions = sessionRepo.findByStatusOrderByCreatedAtDesc("CLEARED")
    return first N sessions
}
```

**AuctionController.java**:
```pseudo
@RestController @RequestMapping("/bid/auction") @Validated @Tag @Slf4j
class AuctionController {
  @Autowired PlaceAuctionBidService bidService
  @Autowired AuctionStatusService statusService

  POST "/": convert PlaceAuctionBidReq -> AuctionBidRequest, call bidService.submitBid
  GET "/status": statusService.getCurrentAuctionStatus()
  GET "/results": statusService.getAuctionResults(auctionId param)
  GET "/history": statusService.getAuctionHistory(limit param)
}
```

### DEV-018: Modify existing files (2 files)

**EapMatchEngine.java** - add Feign methods:
```pseudo
@PostMapping("/v1/auction/bid") ResponseEntity<AuctionBidResponse> submitAuctionBid(AuctionBidRequest)
@GetMapping("/v1/auction/status") ResponseEntity<AuctionStatusDto> getAuctionStatus()
@GetMapping("/v1/auction/config") ResponseEntity<AuctionConfigDto> getAuctionConfig()
@PutMapping("/v1/auction/config") ResponseEntity<AuctionConfigDto> updateAuctionConfig(AuctionConfigDto)
```

**McpApiController.java** - add MCP endpoints:
```pseudo
POST "/auction/bid": build AuctionBidRequest, call bidService, return AuctionBidResponse
GET "/auction/status": call statusService, return AuctionStatusDto
GET "/auction/results": call statusService, return AuctionResultDto
```

---

## Module: eap-wallet

### DEV-019: Modify `src/main/java/.../configuration/config/RabbitMQConfig.java`

```pseudo
// Add beans:
auctionExchange() -> TopicExchange(AUCTION_EXCHANGE)
walletAuctionBidSubmittedQueue() -> durable with DLX
walletAuctionClearedQueue() -> durable with DLX
walletAuctionBidSubmittedBinding() -> bind to AUCTION_BID_SUBMITTED_KEY
walletAuctionClearedBinding() -> bind to AUCTION_CLEARED_KEY
```

### DEV-020: New file `src/main/java/.../application/AuctionBidListener.java`

```pseudo
@Component @Slf4j
class AuctionBidListener {
  @Autowired WalletRepository walletRepository
  @Autowired ObjectMapper objectMapper

  @RabbitListener(queues = WALLET_AUCTION_BID_SUBMITTED_QUEUE) @Transactional
  onAuctionBidSubmitted(AuctionBidSubmittedEvent event):
    wallet = walletRepository.findByUserId(event.userId)
    if null -> log error, return

    totalLocked = event.totalLocked

    if "BUY".equals(event.side):
      if wallet.availableCurrency < totalLocked -> log warn "insufficient balance", return
      wallet.availableCurrency -= totalLocked
      wallet.lockedCurrency += totalLocked
    else: // SELL
      if wallet.availableAmount < totalLocked -> log warn "insufficient amount", return
      wallet.availableAmount -= totalLocked
      wallet.lockedAmount += totalLocked

    walletRepository.save(wallet)
    log.info("Auction bid funds locked: userId={}, side={}, locked={}", userId, side, totalLocked)
```

### DEV-021: New file `src/main/java/.../application/AuctionSettlementListener.java`

```pseudo
@Component @Slf4j
class AuctionSettlementListener {
  @Autowired WalletRepository walletRepository

  @RabbitListener(queues = WALLET_AUCTION_CLEARED_QUEUE) @Transactional
  handleAuctionCleared(AuctionClearedEvent event):
    for result in event.results:
      try:
        wallet = walletRepository.findByUserId(result.userId)
        if wallet == null -> log error, continue

        if "BUY".equals(result.side):
          // Unlock original locked amount
          wallet.lockedCurrency -= result.originalTotalLocked
          if result.clearedAmount > 0:
            // Refund: originalLocked - actual cost
            refund = result.originalTotalLocked - result.settlementAmount
            wallet.availableCurrency += refund
            wallet.availableAmount += result.clearedAmount  // receive energy
          else:
            // Not cleared: full refund
            wallet.availableCurrency += result.originalTotalLocked

        else: // SELL
          wallet.lockedAmount -= result.originalTotalLocked
          if result.clearedAmount > 0:
            // Return unsold amount
            unsold = result.originalTotalLocked - result.clearedAmount
            wallet.availableAmount += unsold
            wallet.availableCurrency += result.settlementAmount  // receive payment
          else:
            // Not cleared: full return
            wallet.availableAmount += result.originalTotalLocked

        walletRepository.save(wallet)
      catch Exception e:
        log.error("Settlement failed for user {}: {}", result.userId, e.getMessage())

    log.info("Auction settlement complete: auctionId={}, MCP={}, MCV={}",
      event.auctionId, event.clearingPrice, event.clearingVolume)
```

---

## Module: eap-mcp

### DEV-022: Modify `src/main/java/com/eap/mcp/client/OrderServiceClient.java`

```pseudo
// Add:
@PostMapping("/mcp/v1/auction/bid") ResponseEntity<AuctionBidResponse> submitAuctionBid(AuctionBidRequest)
@GetMapping("/mcp/v1/auction/status") ResponseEntity<AuctionStatusDto> getAuctionStatus()
@GetMapping("/mcp/v1/auction/results") ResponseEntity<AuctionResultDto> getAuctionResults(@RequestParam auctionId)
```

### DEV-023: New file `src/main/java/com/eap/mcp/tools/mcp/AuctionMcpTool.java`

```pseudo
@Component @RequiredArgsConstructor @Slf4j
class AuctionMcpTool {
  final OrderServiceClient orderServiceClient

  @Tool(name="submitAuctionBid", description="Submit a sealed bid for current auction")
  AuctionBidResponse submitAuctionBid(
    @ToolParam userId, @ToolParam side, @ToolParam stepsJson):
    // Parse stepsJson -> List<BidStep>
    // Build AuctionBidRequest
    // Call orderServiceClient.submitAuctionBid()
    // Return response body

  @Tool(name="getAuctionStatus", description="Get current auction status")
  AuctionStatusDto getAuctionStatus():
    return orderServiceClient.getAuctionStatus().getBody()

  @Tool(name="getAuctionResults", description="Get auction clearing results")
  AuctionResultDto getAuctionResults(@ToolParam auctionId):
    return orderServiceClient.getAuctionResults(auctionId).getBody()
}
```

---

## Event Flow Diagram

```
User submits bid
  -> eap-order AuctionController POST /bid/auction
    -> eap-order PlaceAuctionBidService
      -> eap-matchEngine Feign POST /v1/auction/bid (Redis collect)
      -> eap-order DB save (auction_bids)
      -> RabbitMQ: AuctionBidSubmittedEvent
        -> eap-wallet AuctionBidListener (lock funds)

Scheduler :10 triggers clearing
  -> eap-matchEngine AuctionSchedulerService
    -> close gate (Redis)
    -> read all bids (Redis Lua)
    -> AuctionClearingService.clear() (pure computation)
    -> RabbitMQ: AuctionClearedEvent
      -> eap-order AuctionResultListener (save results + WebSocket push)
      -> eap-wallet AuctionSettlementListener (settle + refund)
```
