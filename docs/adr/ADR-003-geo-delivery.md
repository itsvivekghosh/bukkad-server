# ADR-003: Geo & delivery matching (P-08 caveat / #12 scope)

**Status:** Accepted (binding for all concurrent audit batches)
**Date:** 2026-09-09

## Decision
- No PostGIS. Matching = **Redis GEOSEARCH** over the existing
  `geo:rider:locations` key (already written by RiderLocationTrackingService)
  + a conditional-update load cap; `findFirstByIsActiveTrue` stays as
  fallback. Feature-flagged (`app.delivery.geo-matching.enabled`, default off
  in dev, on in prod overlay only after the flag has soaked).
- Road distance: the OSRM client (`RoadDistanceService`) is **wired off by
  default and deleted if not enabled within one release** — no dead stubs.
  ETA uses haversine + zone factor until OSRM is actually deployed.
- PostGIS stays deferred; a future city-scale geodesic need reopens this ADR.

## Consequences
- `rider_location_updates` keeps its (agent_id, recorded_at) index + the
  landed 72h retention purge; no geometry columns are added.
- Assignment uniqueness/ordering guarantees come from PERF-4's
  UNIQUE(order_id) + conditional updates, independent of the matcher.
