# Implementation Plan: auction-risk-fixes

## 執行順序

所有 task 可按 module 平行執行。eap-wallet 內部 DEV-105 依賴 DEV-104（retry 邏輯要包裹 idempotency guard）。

```
eap-matchEngine: DEV-101 ──┐
                 DEV-102 ──┤  (parallel, no dependency)
eap-order:       DEV-106 ──┤
eap-wallet:      DEV-103 ──┤
                 DEV-104 ──→ DEV-105  (DEV-105 depends on DEV-104)
```

---

## Phase 1: eap-matchEngine（DEV-101, DEV-102 可平行）

### DEV-101: RISK-3 -- DLX Bean Declaration

| 檔案 | 動作 | 說明 |
|------|------|------|
| `src/main/java/com/eap/eap_matchengine/configuration/config/RabbitMQConfig.java` | 修改 | 新增 deadLetterExchange(), deadLetterQueue(), dlqBinding() 三個 Bean |

#### Pseudo Code

```java
// === 新增於 Common section，jsonMessageConverter() 之前 ===

// --- Dead Letter Exchange / Queue (shared, idempotent declare) ---

@Bean
public FanoutExchange deadLetterExchange() {
    return new FanoutExchange(DEAD_LETTER_EXCHANGE);  // "order.dlx"
}

@Bean
public Queue deadLetterQueue() {
    return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();  // "order.dlq"
}

@Bean
public Binding dlqBinding(Queue deadLetterQueue, FanoutExchange deadLetterExchange) {
    return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
}
```

### DEV-102: RISK-6 -- auctionEnabled Single Source

| 檔案 | 動作 | 說明 |
|------|------|------|
| `src/main/java/com/eap/eap_matchengine/application/AuctionSchedulerService.java` | 修改 | 移除 @Value auctionEnabled，改用 auctionRedisService.getGlobalConfig().isAuctionEnabled() |

#### Pseudo Code

```java
// 刪除:
// @Value("${auction.enabled:true}")
// private boolean auctionEnabled;

// openAuction() 中替換:
// if (!auctionEnabled) → if (!auctionRedisService.getGlobalConfig().isAuctionEnabled())

// closeAndClear() 中替換:
// if (!auctionEnabled) → if (!auctionRedisService.getGlobalConfig().isAuctionEnabled())
```

---

## Phase 2: eap-wallet（DEV-103 可先行，DEV-104 再 DEV-105）

### DEV-103: RISK-3 -- DLX Bean Declaration

| 檔案 | 動作 | 說明 |
|------|------|------|
| `src/main/java/com/eap/eap_wallet/configuration/config/RabbitMQConfig.java` | 修改 | 新增 deadLetterExchange(), deadLetterQueue(), dlqBinding() 三個 Bean |

#### Pseudo Code

```java
// === 新增於 jsonMessageConverter() 之後、orderExchange() 之前 ===

// --- Dead Letter Exchange / Queue (shared, idempotent declare) ---

@Bean
public FanoutExchange deadLetterExchange() {
    return new FanoutExchange(DEAD_LETTER_EXCHANGE);
}

@Bean
public Queue deadLetterQueue() {
    return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
}

@Bean
public Binding dlqBinding(@Qualifier("deadLetterQueue") Queue deadLetterQueue,
                          FanoutExchange deadLetterExchange) {
    return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
}
```

### DEV-104: RISK-4 -- Settlement Idempotency

| 檔案 | 動作 | 說明 |
|------|------|------|
| `src/main/resources/db/changelog/db.changelog-wallet-init.xml` | 修改 | 新增 changeSet wallet-011: settlement_idempotency table |
| `src/main/java/com/eap/eap_wallet/domain/entity/SettlementIdempotencyEntity.java` | 新增 | JPA Entity for settlement_idempotency |
| `src/main/java/com/eap/eap_wallet/configuration/repository/SettlementIdempotencyRepository.java` | 新增 | JPA Repository with existsByAuctionIdAndUserIdAndSide() |
| `src/main/java/com/eap/eap_wallet/application/AuctionSettlementListener.java` | 修改 | 注入 SettlementIdempotencyRepository，在 tx 內加 check + insert |

#### Pseudo Code -- Liquibase changeSet

```xml
<changeSet id="wallet-011" author="eap">
    <comment>RISK-4: settlement_idempotency table</comment>
    <createTable tableName="settlement_idempotency" schemaName="wallet_service">
        <column name="id" type="BIGSERIAL"><constraints primaryKey="true" nullable="false"/></column>
        <column name="auction_id" type="VARCHAR(20)"><constraints nullable="false"/></column>
        <column name="user_id" type="UUID"><constraints nullable="false"/></column>
        <column name="side" type="VARCHAR(4)"><constraints nullable="false"/></column>
        <column name="settled_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
            <constraints nullable="false"/>
        </column>
    </createTable>
    <addUniqueConstraint tableName="settlement_idempotency" schemaName="wallet_service"
        columnNames="auction_id, user_id, side" constraintName="uk_settlement_idempotency"/>
</changeSet>
```

#### Pseudo Code -- SettlementIdempotencyEntity.java

```java
@Entity
@Table(name = "settlement_idempotency", schema = "wallet_service",
       uniqueConstraints = @UniqueConstraint(name = "uk_settlement_idempotency",
           columnNames = {"auction_id", "user_id", "side"}))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class SettlementIdempotencyEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "auction_id", nullable = false, length = 20)
    private String auctionId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "side", nullable = false, length = 4)
    private String side;
    @Column(name = "settled_at", nullable = false)
    @Builder.Default
    private LocalDateTime settledAt = LocalDateTime.now();
}
```

#### Pseudo Code -- SettlementIdempotencyRepository.java

```java
public interface SettlementIdempotencyRepository
        extends JpaRepository<SettlementIdempotencyEntity, Long> {
    boolean existsByAuctionIdAndUserIdAndSide(String auctionId, UUID userId, String side);
}
```

#### Pseudo Code -- AuctionSettlementListener 修改

```java
// 新增 field:
@Autowired
private SettlementIdempotencyRepository settlementIdempotencyRepository;

// 在 txTemplate.executeWithoutResult(status -> { ... }) 內，wallet lookup 前:
if (settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
        event.getAuctionId(), result.getUserId(), result.getSide())) {
    log.info("Settlement already processed, skipping: auctionId={}, userId={}, side={}",
            event.getAuctionId(), result.getUserId(), result.getSide());
    return;
}

// 在 walletRepository.save(wallet) 後:
settlementIdempotencyRepository.save(SettlementIdempotencyEntity.builder()
        .auctionId(event.getAuctionId())
        .userId(result.getUserId())
        .side(result.getSide())
        .build());
```

### DEV-105: W-3 -- Optimistic Lock Retry (depends on DEV-104)

| 檔案 | 動作 | 說明 |
|------|------|------|
| `src/main/java/com/eap/eap_wallet/application/AuctionSettlementListener.java` | 修改 | 在 txTemplate 外層加 retry loop (max 3) |
| `src/main/java/com/eap/eap_wallet/application/AuctionBidListener.java` | 修改 | 移除 @Transactional，改用 TransactionTemplate + retry loop |

#### Pseudo Code -- AuctionSettlementListener retry

```java
// 替換 handleAuctionCleared() 中 for-loop 內的 try block:
for (AuctionBidResult result : event.getResults()) {
    int maxRetries = 3;
    boolean settled = false;
    for (int attempt = 1; attempt <= maxRetries && !settled; attempt++) {
        try {
            txTemplate.executeWithoutResult(status -> {
                // idempotency check (DEV-104)
                // wallet lookup + settle + save
                // idempotency record save (DEV-104)
            });
            settled = true;
            settledCount++;
        } catch (ObjectOptimisticLockingFailureException e) {
            if (attempt == maxRetries) {
                log.error("Settlement failed after {} retries: userId={}", maxRetries, result.getUserId(), e);
            } else {
                log.warn("Optimistic lock conflict attempt {}/{}: userId={}", attempt, maxRetries, result.getUserId());
            }
        } catch (Exception e) {
            log.error("Settlement failed for userId={}: {}", result.getUserId(), e.getMessage(), e);
            break; // non-retryable error
        }
    }
}
```

#### Pseudo Code -- AuctionBidListener retry

```java
// 移除 @Transactional
// 新增 @Autowired PlatformTransactionManager transactionManager;

public void onAuctionBidSubmitted(AuctionBidSubmittedEvent event) {
    log.info("Received AuctionBidSubmittedEvent: ...");
    TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
    int maxRetries = 3;
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        try {
            txTemplate.executeWithoutResult(status -> {
                WalletEntity wallet = walletRepository.findByUserId(event.getUserId());
                // ... existing validation + lock logic ...
                walletRepository.save(wallet);
                // ... existing outbox save ...
            });
            break;
        } catch (ObjectOptimisticLockingFailureException e) {
            if (attempt == maxRetries) {
                log.error("Bid fund locking failed after {} retries: userId={}", maxRetries, event.getUserId(), e);
                throw e;
            }
            log.warn("Optimistic lock conflict attempt {}/{}, retrying...", attempt, maxRetries);
        }
    }
}
```

---

## Phase 3: eap-order（DEV-106 無依賴，可平行）

### DEV-106: RISK-5 -- UNIQUE Constraint Change

| 檔案 | 動作 | 說明 |
|------|------|------|
| `src/main/resources/db/changelog/db.changelog-auction.xml` | 修改 | 新增 changeSet auction-005: drop old + add new UNIQUE constraint |
| `src/main/java/com/eap/eap_order/domain/entity/AuctionBidEntity.java` | 修改 | @UniqueConstraint columnNames 從 3 欄改為 2 欄 |

#### Pseudo Code -- Liquibase changeSet

```xml
<changeSet id="auction-005" author="eap">
    <comment>RISK-5: Change auction_bids UNIQUE from (auction_id, user_id, side) to (auction_id, user_id)</comment>
    <dropUniqueConstraint tableName="auction_bids" schemaName="order_service"
        constraintName="uk_auction_bids_auction_user_side"/>
    <addUniqueConstraint tableName="auction_bids" schemaName="order_service"
        columnNames="auction_id, user_id" constraintName="uk_auction_bids_auction_user"/>
</changeSet>
```

#### Pseudo Code -- AuctionBidEntity.java

```java
// 修改 @Table annotation:
@Table(name = "auction_bids", schema = "order_service",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_auction_bids_auction_user",
           columnNames = {"auction_id", "user_id"}))
```

---

## Schema Migration Summary

### eap-order (Liquibase)

| changeSet | 檔案 | 內容 |
|-----------|------|------|
| auction-005 | `src/main/resources/db/changelog/db.changelog-auction.xml` | dropUniqueConstraint(uk_auction_bids_auction_user_side) + addUniqueConstraint(uk_auction_bids_auction_user) |

### eap-wallet (Liquibase)

| changeSet | 檔案 | 內容 |
|-----------|------|------|
| wallet-011 | `src/main/resources/db/changelog/db.changelog-wallet-init.xml` | createTable(settlement_idempotency) + addUniqueConstraint(uk_settlement_idempotency) |

---

## 任務依賴關係

| Task ID | Module | Title | Depends On |
|---------|--------|-------|------------|
| DEV-101 | eap-matchEngine | RISK-3: DLX bean declaration | -- |
| DEV-102 | eap-matchEngine | RISK-6: auctionEnabled read from Redis | -- |
| DEV-103 | eap-wallet | RISK-3: DLX bean declaration | -- |
| DEV-104 | eap-wallet | RISK-4: Settlement idempotency | -- |
| DEV-105 | eap-wallet | W-3: Optimistic lock retry | DEV-104 |
| DEV-106 | eap-order | RISK-5: UNIQUE constraint change | -- |
