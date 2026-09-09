# ADR-004: JWT verification topology (P-10 decision debt)

**Status:** Accepted (binding for all concurrent audit batches)
**Date:** 2026-09-09

## Decision
- **Keep verification at both layers** (gateway bucketing + service filter)
  until identity completes the RS256+JWKS cutover. HS256 verification is
  ~0.1 ms; the double-verify cost is not the bottleneck (PERF-1 removed the
  blocking-JWKS cliff that made the edge verify dangerous).
- Identity issues tokens with `jti`, `iss`, `aud`, `iat`, `exp` + role claims.
  Cutover order: (1) identity issues RS256 and serves JWKS while still
  accepting HS256; (2) validators prefer JWKS keys and fall back to the HMAC
  secret during the grace window; (3) HS256 signing is retired in a later
  release. No flag flips at the gateway in this cutover.
- Gateway terminating + reissuing an internal token is NOT adopted now
  (depends on the deferred service mesh).

## Consequences
- `PlatformJwtValidator` (rewritten in PERF-1) gains RS256/JWKS support via
  the existing stale-while-revalidate cache — same single-flight, backoff and
  pre-warm machinery; no second validator stack.
- V-15's ≥32-byte fail-fast remains for the HMAC grace path only.
