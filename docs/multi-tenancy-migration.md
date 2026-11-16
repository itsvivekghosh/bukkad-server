# Multi-tenancy migration — tenant_id data isolation

This document implements the multi-tenancy design (referenced as
docs/architecture-audit.md §1.3; that file does not exist in the repo, so this
document is the authoritative spec for the tenant_id rollout). It covers the
schema migration, the backfill strategy, the Hibernate filter adoption plan,
the RLS rollout, and the self-service onboarding surface.

## 1. Design

Two layered, independently effective isolation mechanisms:

| Layer | Mechanism | Enforced by |
|-------|-----------|-------------|
| Application | `tenant_id` column + Hibernate `@Filter(name = "tenant")` | Hibernate, per session |
| Database | PostgreSQL RLS policy `tenant_isolation` | PostgreSQL, per connection |

Shared tenant context: `com.bhukkad.common.tenant.TenantContext` (platform-lib)
— a `ThreadLocal<String>` tenant id implementing Hibernate's
`CurrentTenantIdentifierResolver`, with static `setTenantId` /
`getTenantId` / `clearTenantId` helpers. The context is bound per request and
cleared in a `finally` block, so it never leaks across threads in a pool.

`TenantContext` is also registered as Hibernate's
`hibernate.tenant_identifier_resolver` (`TenantContext.tenantIdentifierResolver()`).
With the default `hibernate.multi_tenancy=NONE` Hibernate never invokes the
resolver, so existing behavior is unchanged. Setting
`hibernate.multi_tenancy=SCHEMA` later activates schema-based routing through
the same resolver.

## 2. Schema migration (Flyway)

The column is added **NULLABLE**. Existing rows are not touched automatically,
per the requirement that no data is migrated without an explicit backfill.

- `services/identity/src/main/resources/db/migration-pg/V13__tenant_isolation.sql`
  — `customers, addresses, admins, users, delivery_agents, restaurant_owners,
    device_tokens, favorite_restaurants, customer_memberships,
    customer_notification_preferences, consent_records`
- `services/admin/src/main/resources/db/migration-pg/V8__tenant_isolation.sql`
  — `support_tickets, disputes`

Per table: `ADD COLUMN IF NOT EXISTS tenant_id UUID`, a btree index
`idx_<table>_tenant_id`, `ENABLE ROW LEVEL SECURITY`, and policy

```sql
CREATE POLICY tenant_isolation ON public.<table>
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
```

Notes:

- The column is nullable in the migration and remains nullable until the
  backfill script runs and sets `SET NOT NULL`. Entity mappings map
  `tenant_id` as nullable during the transition.
- `current_setting(..., true)` (missing_ok) is used: an unset
  `app.current_tenant` yields NULL and the policy denies access rather than
  throwing "configuration parameter not found".
- Policies are created **not FORCED**, so the table owner (the application
  connection) still bypasses RLS until `ALTER TABLE ... FORCE ROW LEVEL
  SECURITY` is applied after backfill. Existing behavior is unchanged on day 1.
- Intentionally not altered: `tenants` (the registry itself),
  `api_keys` / `membership_plans` (platform reference data),
  `outbox_events`, `idempotency_records`, `dead_letter_events`,
  `saga_instances`, `saga_steps` (platform infra, not domain data).

## 3. Backfill strategy (do not run automatically)

File: `scripts/db/backfill_tenant_id.sql`

1. Apply V13 / V8.
2. Run the backfill script against each service database. It assigns every
   pre-existing row to the platform tenant
   `00000000-0000-0000-0000-000000000001` and then sets `tenant_id NOT NULL`.
   The script is idempotent (`WHERE tenant_id IS NULL`).
3. Optionally insert a `tenants` row for the platform tenant (no FK is
   declared, so the UUID alone suffices).
4. Only after backfill: enable enforcement — each request's connection runs
   `SET app.current_tenant = <tenant-uuid>`, then apply
   `ALTER TABLE public.<table> FORCE ROW LEVEL SECURITY` per table.

There is no automatic data migration: Flyway applies the schema change only;
operators run the backfill script explicitly per database.

## 4. Per-entity Hibernate filter adoption plan

Annotated (representative subset — the pattern is repeatable):

- Identity: `Customer`, `Address`
- Admin: `SupportTicket`, `Dispute`

Each annotated entity gains:

```java
@FilterDef(name = "tenant", parameters = @ParamDef(name = "tenantId", type = "uuid"))
@Filter(name = "tenant", condition = "tenant_id = :tenantId")
@Column(name = "tenant_id")
private UUID tenantId;

@PrePersist
private void prePersist() {
    if (tenantId == null) {
        tenantId = TenantContext.getTenantId();
    }
}
```

Behavior contract:

- Filter **disabled** (default): all rows visible — existing callers unaffected.
- Filter **enabled** with `:tenantId` bound to `TenantContext.getTenantId()`:
  only the current tenant's rows are returned.
- New rows inherit the current tenant id from `TenantContext` on persist.

Adoption plan for remaining entities: annotate on a per-service basis as teams
backfill each table; add `tenant_id` to the entity and register
`@FilterDef`/`@Filter` following the pattern above. Platform infra tables
(outbox, idempotency, dead letter, saga) are deliberately excluded.

Filter enablement pattern (integration test
`TenantIsolationPostgresIntegrationTest`):

```java
Session session = entityManager.unwrap(Session.class);
session.enableFilter("tenant").setParameter("tenantId", TenantContext.getTenantId());
try { ... query ... } finally { session.disableFilter("tenant"); }
```

## 5. RLS rollout

Day 1 (this change): policies created, not forced. No runtime effect for the
table-owning application connection; existing queries unaffected.

Day 2 (after backfill + NOT NULL):

1. Application sets `SET app.current_tenant = <tenant-uuid>` on each request
   connection (via connection provider / init SQL / the tenant request filter).
2. `ALTER TABLE public.<table> FORCE ROW LEVEL SECURITY` per table — now the
   owner is subject to the policy too; cross-tenant access is impossible at the
   database level even if the application filter is bypassed.

Deny-by-default: if `app.current_tenant` is unset on a connection, `current_setting(..., true)`
returns NULL and the policy matches no rows.

## 6. Self-service onboarding (admin surface)

Tenant lifecycle is owned by the identity service (`TenantController`,
`/api/v1/tenants`); the admin service has no TenantController of its own.

Endpoints (`@PreAuthorize("hasRole('ADMIN')")`):

- `POST /api/v1/tenants` — create tenant (name, domain).
- `GET /api/v1/tenants/{tenantId}/verify-domain` — placeholder domain
  verification (confirms the domain is registered to the tenant).
- `GET /api/v1/tenants/{tenantId}/features/{flag}` — per-tenant feature-flag
  override (Redis hash `bhukkad:tenant:features`, shadows the global flag).
- `PUT /api/v1/tenants/{tenantId}/features/{flag}` — set a per-tenant override.

## 7. Metering and billing

- `com.bhukkad.admin.analytics.domain.service.TenantMeteringService` — per-tenant
  quota tracking with Redis sorted sets (`bhukkad:metering:{tenantId}:{metric}`,
  score = epoch millis, rolling-window prune on read). `consumeQuota` runs
  atomically via a Lua script (prune → count → reject-or-record).
- `com.bhukkad.admin.analytics.domain.service.TenantBillingWebhookHandler` —
  placeholder interface for billing webhook ingestion (e.g. Stripe). No
  implementation yet; wire the provider connector here. Implementations must be
  idempotent (webhooks may be redelivered).

## 8. Tests

- `com.bhukkad.common.tenant.TenantContextTest` — ThreadLocal isolation across
  threads, clear semantics, resolver delegation.
- `com.bhukkad.identity.integration.repository.TenantIsolationPostgresIntegrationTest`
  — Hibernate tenant filter hides cross-tenant rows; RLS policy (forced in the
  test transaction) hides cross-tenant rows via `app.current_tenant`; no-filter
  behavior preserved.

## 9. Verification

Run:

```
./mvnw -f services/pom.xml -pl identity,admin -am test
```

Flyway applies V13 / V8 against Testcontainers PostgreSQL in the integration
test slices, so migration validity is part of the test run.
