# Bhukkad Event Catalog

**Status:** living authority for cross-service async events (ADR A2).
This is the human-readable view over the event types defined in
`services/platform-lib/.../common/event/` + the per-service `*Events`
publishers. **schemaVersion is required on every payload** (ADR A2); unknown
versions are rejected-and-alerted on consume.

- **Transport:** Kafka / Redpanda (`cluster-a`), at-least-once via the per-service
  transactional **outbox** (ADR A1). Redis pub/sub is **cache-eviction only** (ADR A10).
- **Envelope:** `com.bhukkad.common.event.PlatformEventMessage` —
  `{ eventId, eventType, schemaVersion, occurredAt, aggregateId, correlationId,
     traceparent, payload }`, `payload` being a JSON string versioned by `schemaVersion`.
- **Idempotency:** consumers de-dupe on `eventId`.

## Topics

| Topic | Owner (producer) | Notes |
|---|---|---|
| `order.events.v1` | order | primary order lifecycle stream (`OrderEventPublisher.TOPIC`) |
| `bhukkad.platform.events` | (default outbox prefix) | fallback topic when a service has no dedicated one; per-type suffix `bhukkad.<eventtype-lowercase>` |

Producers route via `KafkaPlatformEventPublisher.publishForResult` (row flips
PUBLISHED only on broker ack); DLQ = `order.events.v1.dlt`.

## Event types

| Event | Producer | Payload (v1 fields) | Consumers | Status |
|---|---|---|---|---|
| `CustomerRegistered` | identity | `customerId, email, mobile` | notification (welcome), search | Active |
| `AddressChanged` | identity | `customerId, addressId` | search (reindex) | Active |
| `RestaurantCreated` | restaurant | `restaurantId, cuisineId` | search (index) | Active |
| `RestaurantAvailabilityChanged` | restaurant | `restaurantId, isOpen` | search, gateway edge cache | Active |
| `MenuChanged` | restaurant | `restaurantId, menuVersionId` | search (reindex) | Active |
| `OrderCreated` | order | `orderId, customerId, restaurantId` | notification, search (trending `MenuItemCacheSyncer`, ADR A7), survey (order-items snapshot) | Active |
| `OrderStatusChanged` | order | `orderId, status` | notification, delivery, admin CQRS | Active |
| `OrderPickedUp` | delivery | `orderId, agentId` | notification, live SSE fan-out | Active |
| `OrderDelivered` | delivery | `orderId, agentId` | notification, payment (settlement trigger, W2), admin CQRS | Active |
| `DeliveryAssigned` | delivery | `orderId, agentId` | notification, live SSE | Active |
| `PaymentSettled` | payment | `orderId, settlementId, amount` | admin CQRS, restaurant (wallet credit) | Active (W2 wiring) |
| `WalletCredited` | payment | `walletUserId, amount, referenceType, referenceId` | order/growth (referral payout, W3) | Active |
| `PaymentSettlementRunCompleted` | payment | `runId, runDate, restaurantsSettled, rowsSettled, totalNet` | admin CQRS dashboards; payout pipeline (W2) | Active (W2/G1 scheduler) |
| `SurveySubmittedEvent` | survey | `restaurantId, menuItemId, answerIds` | analytics rollups (fire-and-forget) | Active |

## Known drift to reconcile (from the gap analysis, W1–W3)

- **`OrderCreated` had three incompatible shapes** across the monolith publisher,
  the outbox payload, and the search consumer. Canonical v1 is defined above;
  the survey `OrderItemsSnapshotConsumer` consumes an extended payload under
  `schemaVersion=2` (adds item breakdown) — the two-version ceiling from ADR A2
  means v1 stays live for one wave after consumers support v2.

## Adding / evolving an event (contract checklist)

1. Add the `TYPE_*` constant + `enqueue(...)` in the owning service's `*Events` class.
2. Bump nothing existing — a **new** payload shape is a **new** `schemaVersion`
   (V2 subject) per ADR A2; max 2 live versions, consumers migrate within one wave.
3. Register the event here (row + topic + consumers).
4. Add a consumer-side idempotent handler keyed on `eventId` and a
   `schemaVersion` guard that rejects-and-alerts on unknown versions.
5. Add a catalog test: producer emits, consumer ingests, DLQ on reject.
