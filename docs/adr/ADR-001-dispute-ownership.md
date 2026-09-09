# ADR-001: Dispute ownership (R-04 close)

**Status:** Accepted (binding for all concurrent audit batches)
**Date:** 2026-09-09

## Decision
- **supportticket** is the single system-of-record for the dispute lifecycle
  (create → investigate → resolve). It emits `dispute_resolved` via its own
  outbox and NEVER credits wallets directly.
- **payment** consumes `dispute_resolved` and performs the wallet/ledger credit
  in one payment transaction (idempotent by the dispute id + eventId claim).
- **payment's own `domain/Dispute` + `DisputeService` write path is removed**
  (keep read-only compatibility shims only if consumers exist — verify first).
- Gateway routes `/api/v1/admin/disputes/**` → `supportUri` (was orderUri).

## Consequences
- Exactly one crediting path for a dispute (V-01 class cannot recur).
- supportticket's cross-domain Order/User/GiftCard repositories become
  read-models only (R-08 `GRANT SELECT ONLY`-style enforcement).
- Refunds stay hard-capped at the order's paid total (batch D rule preserved).

## Verification
- ArchUnit/CI: only supportticket writes dispute tables; only payment writes
  wallet adjustments.
- Replay drill: duplicate `dispute_resolved` → single credit.
