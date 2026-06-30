# EAP Match Engine

`eap-matchEngine` is the matching and auction execution core.

It keeps the Redis order book, performs atomic matching with Lua scripts, and publishes match results back to the rest of the system.

## Responsibilities

- Maintain the order book in Redis
- Execute continuous matching
- Execute timed auction flows
- Publish match and clearing results

## What belongs here

- Price/time ordering logic
- Redis Lua atomic operations
- Matching and clearing rules
- Idempotent handling for match replay

## What does not belong here

- Wallet balance validation
- AI orchestration
- Generic API aggregation

## Main dependencies

- Redis / Redisson
- RabbitMQ for async events
- `eap-common` for shared DTOs and events

## Run

```bash
./gradlew :eap-matchEngine:bootRun
```

Default port: `8082`

## Notes

- This service is the price-time priority boundary.
- Keep Redis scripts short and deterministic.
