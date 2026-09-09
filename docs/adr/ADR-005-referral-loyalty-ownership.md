# ADR-005: Referral & loyalty ownership (R-E / #4)

**Status:** Accepted (binding for all concurrent audit batches)
**Date:** 2026-09-09

## Decision
- **referral module** owns referral-code generation and the
  apply/complete lifecycle. identity's `ReferralService` stops generating
  codes ("BK" + id + modulo tail is deleted); identity calls referral's
  internal API (service-JWT) or consumes referral events for display.
- Code generation = one owner, collision-safe (retry-on-unique-violation with
  an unbounded alphabet tail; never `id % 10000`).
- **growth** owns loyalty points as a durable ledger:
  `loyalty_points_ledger` (append-only CREDIT/DEBIT rows) is the source of
  truth; the Redis counter becomes a cache warmed from the ledger. Redemption
  = conditional single-statement decrement (`WHERE points >= :amt`), never a
  Redis-only TOCTOU. Credit endpoint requires service-JWT + idempotency key
  (scope `LOYALTY_CREDIT`) + abuse ceilings (per-customer daily cap).
- Referral apply is idempotent: a referred customer can never be re-bound
  (partial unique index on `referred_by` / early-return guard, both).

## Consequences
- A Redis flush can no longer destroy loyalty balances (rebuild = ledger sum).
- Nightly reconciliation job (ShedLock) recomputes counters from the ledger
  and alerts on drift > 0.
