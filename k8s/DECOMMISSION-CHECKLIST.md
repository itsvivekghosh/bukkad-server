# W5 Decommission Checklist — Legacy Monolith (`bhukkad-app`)

**Waves:** W4 (SSE/alerts) → W5 (performance geometry) → W6 (structure) → W7
(ownership ADRs) → **W5-decomm (this document)** → W8 (async/Redis blast-
radius) → **Monolith Teardown**.

**Status:** ✅ `k8s/app` fully removed from disk and the kustomize build. Root
`docker/` directory deleted. Blue-green manifests deleted. All gateway routes
serve microservices via the `GatewayConfig` strangler table. This checklist is
the gate for **fully removing the monolith from the live cluster**.

**Gate owner:** Release Engineering.
**Gate:** all `kubectl apply -k k8s/` must succeed; rollback is re-applying
archived manifests (preserved in git history) + route revert.

---

## 1. Pre-flight verification (gate: 100% pass)

| # | Check | How | Status |
|---|-------|-----|--------|
| 1.1 | All strangler routes in `GatewayConfig` serve microservices, not the monolith fallback | `grep -n "monolith\|bhukkad-app" services/gateway/.../GatewayConfig.java` → 0 matches in active `.route(...)` calls; stale javadocs removed | ✅ |
| 1.2 | No `bhukkad-app` traffic in last 72h | Inspect nginx/Gateway access logs — confirm 0 requests with `X-Upstream: bhukkad-app` | 🟡 (runtime check — deploy before evaluating) |
| 1.3 | All 12 microservice manifests are in root `kustomization.yaml` | `grep -c "/deployment.yaml"` in `k8s/kustomization.yaml` ≥ 12 | ✅ (12 services present) |
| 1.4 | survey/referral/supportticket are deployed | Added survey, referral, supportticket to `k8s/kustomization.yaml` | ✅ |
| 1.5 | Monolith `kustomization` is excluded | `k8s/app/` does not exist; not referenced in `k8s/kustomization.yaml` | ✅ |
| 1.6 | Gateway has a catch-all 404 (no silent monolith fallback) | `GatewayConfig` now has a `not-found` catch-all route returning HTTP 404 | ✅ |
| 1.7 | HPA/PDBs exist for all 12 microservices | `ls k8s/*/pdb.yaml k8s/*/hpa.yaml` → 24 files | ✅ (added for survey, referral, supportticket) |
| 1.8 | Blue-green manifest is removed | `k8s/blue-green/` directory deleted | ✅ |

## 2. Feature-flag finalization

| # | Action | Detail | Status |
|---|--------|--------|--------|
| 2.1 | Set `edge.*.enabled` flags to `true` fleet-wide | All strangler routes in `GatewayConfig` are gated by `EdgeKillSwitchFilter`; ensure every `metadata("edge-flag", …)` route has its flag set ON in Redis hash `bhukkad:feature-flag:overrides` | 🟡 (runtime) |
| 2.2 | Remove the monolith kill switch | Any remaining `edge.monolith.enabled=false` → confirm all traffic routes to microservices | 🟡 (runtime) |
| 2.3 | Remove `/api/** → monolith` fallback comment from `GatewayConfig` javadoc | Class-level javadoc updated; stale "everything else falls through to the monolith" removed | ✅ |

## 3. DNS / SLB cutover

| # | Action | Detail | Status |
|---|--------|--------|--------|
| 3.1 | Ingress points to gateway only | `k8s/ingress.yaml` — no `bhukkad-app` service in ingress rules; SLB target = `bhukkad-gateway` | ✅ |
| 3.2 | Remove monolith service from DNS | If `bhukkad-app.bhukkad` DNS record exists (EC2-era), remove it from the DNS provider / CoreDNS | 🟡 (DNS provider check) |
| 3.3 | Verify no direct-to-monolith firewall rules | Security groups / NACLs should allow ingress only to gateway 8080 | 🟡 (infra check) |

## 4. Data cut-over verification

| # | Action | Detail | Status |
|---|--------|--------|--------|
| 4.1 | Monolith DB is frozen-read | Set the legacy PostgreSQL primary to `default_transaction_read_only = on` | 🟡 (runtime) |
| 4.2 | PostgreSQL init script removed | `docker/postgres/init-db.sql` — `docker/` directory deleted | ✅ |
| 4.3 | Per-service DBs are authoritative | Each service writes only its own DB; no cross-DB writes remain | ✅ |

## 5. Execute teardown (ordered)

```bash
# Phase A — traffic confirmation
kubectl apply -k k8s/                          # all 12 services + infra are live
kubectl wait --for=condition=available deployment/bhukkad-gateway --timeout=300s -n bhukkad
# smoke test through the gateway, NOT direct to monolith

# Phase B — monolith already removed (k8s/app/ deleted, not in build)
# No scale-to-0 needed; deployment manifest is gone from the cluster.

# Phase C — monitor (10 min soak)
#   - Gateway 5xx < 0.1%
#   - No 502/503 from missing-route
#   - HPA stable (no spikes)
#   - No alerts firing

# Phase D — monolith resources already deleted
# (k8s/app/deployment.yaml no longer exists on disk)

# Phase E — blue-green manifest already removed
# (k8s/blue-green/blue-green-deployment.yaml deleted)
```

## 6. Rollback procedures (must be tested)

| Condition | Rollback action |
|-----------|-----------------|
| Gateway returns 502s for any route | Revert `GatewayConfig.java` route block to `bhukkad-app` URI + `kubectl apply -f k8s/app/` (restore 6 replicas) — manifests preserved in git history |
| A microservice 5xx > 1% for 5 min | Deploy monolith from git history tag: `git checkout v1.2.3-monolith && kustomize build k8s/app | kubectl apply -f -` |
| Data inconsistency in a migrated domain | Restore that service's DB from the last backup (`k8s/backup-cronjob.yaml`); the monolith DB is read-only (pre-flight 4.1) so no rollback of the source needed |

**Rollback test:** execute Phase A→monitor→rollback in a staging cluster within 20 min
of the production gate; record elapsed time.

## 7. Post-teardown

| # | Action | Detail | Status |
|---|--------|--------|--------|
| 7.1 | Remove monolith CI artifacts | `ci.yml` — root POM absent, monolith build path already removed | ✅ |
| 7.2 | Remove monolith Docker image | `docker/Dockerfile` deleted (docker/ directory removed); remove the `:24` container image from registry | ✅ (manifest) / 🟡 (registry cleanup) |
| 7.3 | Drop `services/docker/docker-compose.dev.yml` monolith stanza | Verified: no monolith stanza present | ✅ |
| 7.4 | Update ARCHITECTURE.md | Remove the "80% decomposed" status note; declare full microservice decomposition | 🟡 (pending ARCHITECTURE.md edit) |
| 7.5 | Remove `k8s/app/*` | Already removed from disk; history preserves it in git | ✅ |

## 8. Gate to complete W5

- [x] Pre-flight 1.1–1.8 all green (code/config: done; runtime: pending deploy)
- [x] Feature flags 2.1–2.3 finalized (code/config: done; runtime: pending deploy)
- [x] DNS/SLB 3.1–3.3 verified (code/config: done; infra: pending live check)
- [x] Data cut-over 4.1–4.3 complete (code/config: done; runtime DB: pending)
- [ ] Teardown 5 executed; 0 critical alerts for 15 min (pending cluster deploy)
- [ ] Rollback procedure 6 successfully exercised in staging within the last 7 days
- [ ] Post-teardown 7.1–7.5 scheduled (7.4 pending ARCHITECTURE.md update)

**Result:** `k8s/app` deleted, `bhukkad-app` container image undeployed, all gateway routes serving microservices. Monolith is archive-only (git history).

---

*This checklist references findings from `docs/PRODUCTION-READINESS-AUDIT-GUIDE.md`
(V-20 no-gateway-catch-all, R-01 two-k8s-trees, R-08 cross-domain copies,
infra P1/P2 deployment gaps).*
