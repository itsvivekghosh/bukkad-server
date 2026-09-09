# ADR-002: Search engine (P-06/P-08/#11 decision)

**Status:** Accepted (binding for all concurrent audit batches)
**Date:** 2026-09-09

## Decision
- **PostgreSQL is the search engine** (tsvector + pg_trgm + the landed
  `varchar_pattern_ops` prefix indexes). No Elasticsearch/OpenSearch.
- Sync is **event-driven**: services emit domain events over the (now durable)
  event backbone; the search service consumes and maintains its read tables.
- ES adoption is deferred until a measured need (typo-tolerance at scale,
  geo+fuzzy scoring beyond pg_trgm) is demonstrated in traces/metrics.

## Consequences
- The search module keeps its bounded LIKE queries (batch D) as the
  compatibility path; tsvector/`pg_trgm` migration is additive when landed.
- `restaurant_search`/`menu_item_search` stay the read tables; population
  moves from "push endpoints nobody calls" to Kafka consumers + a periodic
  reconciliation sweep (bounded, ShedLock).
- Delete propagation: menu-item deleted events remove rows (no orphan hits).
