# ADR-006: Resource-level authorization decision model

**Status:** Accepted  
**Date:** 2026-11-15

## Decision

- Platform-lib owns a self-contained ABAC engine under
  `com.bhukkad.common.security.authz`. An `AuthorizationRequest` carries the
  validated JWT subject, roles/scopes, action, resource type/id, and
  server-derived attributes. `AuthorizationService` selects a registered
  `AuthorizationPolicy` by id and returns an `AuthorizationDecision`.
- Authorization fails closed: unknown policies, unsupported requests, missing
  ownership facts, and policy exceptions are denials. The order read policy
  (`order-ownership`) allows a customer to read only their own order and
  allows `ADMIN` to read any order.
- Every decision increments `authorization_decisions{result,policy}`. Denials
  are emitted through the existing `SecurityEventLogger` security-event
  stream with subject, action, resource, policy, and reason.
- Services remain the enforcement point for resource ownership because they
  own the resource data. The gateway has a feature-gated authorization-filter
  skeleton (`app.authz.enabled`, default `false`) using the same platform
  engine; it does not embed OPA.
- The local engine is the OPA-compatible seam. A future OPA sidecar may
  evaluate the same request/decision contract over HTTP and be substituted
  behind `AuthorizationService` without changing service callers.

## Consequences

- Controller-level ad-hoc ownership checks on the order read path are replaced
  by the shared policy decision; the check is not duplicated.
- The gateway continues to pass authentication through until the flag and an
  applicable policy are enabled.
- Policy identifiers are bounded, low-cardinality values and must not be
  derived from client input.
