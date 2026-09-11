# In-cluster TLS Runbook (flag-gated, k8s/components/tls-internal)

Status: **flag OFF by default**. The base build is untouched (plaintext,
dev-boot compatible). Enabling TLS = one component line in
`k8s/kustomization.yaml` + the flip order below.

```
components:
  - components/redpanda
  - components/tls-internal     # ← the TLS flag
```

Everything is namespaced to `bhukkad` (no ClusterIssuer, no cluster-scoped
objects). The Java services are **not** modified by this flag — their boots
stay green whether or not it is on.

---

## 1. What the flag ships

| Layer | Object | Effect when flag is ON | Breaking? |
|---|---|---|---|
| CA | `Issuer bhukkad-selfsigned-issuer` → `Certificate bhukkad-internal-ca` (isCA) → `Issuer bhukkad-internal-ca-issuer` | cert-manager internal CA (10y) | no |
| Postgres | `Certificate bhukkad-postgres-tls` + Deployment patch (`ssl=on`, `ssl_cert_file/ssl_key_file`) | server **offers** TLS on 5432, still accepts plaintext | no |
| Postgres client | `bhukkad-config` patch: `DB_SSL_ENABLED: "true"` + `sslmode=require` JDBC URLs (primary + replica) | services reconnect with TLS | no (encrypt-only; no CA verification — see §4) |
| Redpanda | `Certificate bhukkad-redpanda-tls` (SANs: all 3 brokers + headless svc) + `redpanda-tls-config` ConfigMap + StatefulSet patch | extra **TLS listener :9095**; `PLAINTEXT :9092` stays up; SASL superuser bootstrap Job runs | no |
| Redis | `Certificate bhukkad-redis-tls` + Deployment patch (cert mounted at `/etc/redis-tls`) | cert pre-positioned only — server stays plaintext until §5 flip | no |
| Nginx | `Certificate bhukkad-nginx-tls` + ConfigMap patch (`bhukkad-api-tls.conf`) + Deployment patch (:443 + cert) + Service patch (:443) | secondary router serves `https://…:443`; `:80` edge unchanged | no |

Key detail (Redpanda): rpk v23.3 has no CLI TLS flags — per-listener TLS comes
from the redpanda.yaml config file. The component mounts
`redpanda-tls-config` (ConfigMap) → copied by a new init container into a
**writable emptyDir** → passed via `--config` (rpk persists broker config
updates to its config file, so a read-only ConfigMap mount would break it).
The listener named `tls` matches the `TLS://` scheme in
`--kafka-addr`/`--advertise-kafka-addr`.

Key detail (Postgres): ssl key files mount with `defaultMode: 0440`
(root-owned, group-readable via `fsGroup: 999`) — PostgreSQL accepts
root-owned 0640 keys; anything looser is refused at startup.

---

## 2. Prerequisites

1. **cert-manager** installed cluster-wide (CRDs `cert-manager.io/v1`).
2. **Vault `bhukkad/prod/redpanda`** provisioned with `superuser_username` /
   `superuser_password` (mapped via `k8s/external-secret.yaml`; placeholders
   in `k8s/secrets.yaml` must never hold real values).
3. The internal CA is namespaces-bound; clients that verify certificates need
   `tls.crt` of the `bhukkad-internal-ca` secret.

## 3. Flip order

```bash
# 0. Dry-render with the flag on and diff — no surprises:
kustomize build k8s/ | kubectl diff -f - || true     # after enabling the line

# 1. Enable the flag (add the component line) and apply:
kubectl apply -k k8s/

# 2. Wait for issuance — every Certificate must be READY=True:
kubectl -n bhukkad get certificates

# 3. Let the patched workloads roll (postgres, redpanda, redis, nginx roll
#    automatically; the config patch triggers service rollouts too):
kubectl -n bhukkad rollout status deploy/bhukkad-postgresql
kubectl -n bhukkad rollout status statefulset/redpanda

# 4. Bootstrap the Redpanda SASL superuser (Job retries until the broker is
#    reachable; inspect if it exhausts backoffLimit):
kubectl -n bhukkad get job redpanda-superuser-bootstrap
```

### Verification (per endpoint)

```bash
# Postgres — TLS offered on 5432, cert from the internal CA:
openssl s_client -connect bhukkad-postgresql.bhukkad.svc.cluster.local:5432 -starttls postgres </dev/null | openssl x509 -noout -subject -issuer

# Redpanda — TLS listener answers on 9095 (broker pod or port-forward):
kubectl -n bhukkad exec redpanda-0 -- rpk cluster info \
  -X brokers=redpanda-0.redpanda-headless.bhukkad.svc.cluster.local:9095 \
  -X tls.enabled=true -X tls.ca=/etc/redpanda/certs/ca.crt

# Nginx — 443 serves the internal-CA leaf:
openssl s_client -connect <nginx-endpoint>:443 </dev/null | openssl x509 -noout -subject -issuer
```

## 4. Postgres clients (done by the flag, verify after rollout)

The component patch sets `DB_SSL_ENABLED: "true"` and swaps both JDBC URLs to
`sslmode=require` (encrypted, no hostname/CA verification — acceptable for the
in-cluster internal CA; upgrading to `verify-ca`/`verify-full` requires
importing `tls.crt` from `bhukkad-internal-ca` into the JVM truststore and is
a documented follow-up, not part of this flip). Confirm one service pod
actually negotiated SSL:

```bash
kubectl -n bhukkad exec deploy/bhukkad-postgresql -- psql -U "$POSTGRES_USER" \
  -c "SELECT usename, ssl FROM pg_stat_ssl WHERE ssl = true;"
```

## 5. Redis TLS (manual, breaking — separate window)

The cert is mounted but the server still serves plaintext. To flip (both sides
in the same maintenance window; Lettuce/RedisCacheService/RateLimitService and
the backup CronJob's redis-cli reconnect afterwards):

1. `k8s/redis/configmap.yaml` (or a live `kubectl edit configmap
   bhukkad-redis-config`): uncomment the `port 0` / `tls-port 6379` /
   `tls-cert-file` / `tls-key-file` / `tls-ca-cert-file` block.
2. `k8s/configmap.yaml`: uncomment `SPRING_DATA_REDIS_SSL_ENABLED: "true"`
   (client) — keep `REDIS_HOST`/`REDIS_PORT` unchanged (same port number).
3. Rollout restart redis, then the services.

**Limitation (coordination):** `redis/redis-sentinel.yaml` does not yet speak
TLS — do not flip the master before the sentinel pair is TLS-enabled too, or
sentinel monitoring will fail. The nightly `backup-redis.sh` needs
`REDIS_SSL`-aware redis-cli flags when the flip lands.

## 6. Redpanda client TLS + SASL (blocked on platform-lib — coordination)

The broker-side TLS listener and the SCRAM superuser exist after §3, but
**clients cannot move yet**: platform-lib's `KafkaPlatformConfig` builds its
consumer/producer maps from `app.events.external.kafka.*` only and reads no
TLS/SASL properties. The agreed env contract is commented in
`k8s/configmap.yaml` (`KAFKA_SSL_*`, `KAFKA_SASL_*`, port-9095 bootstrap).
Once the platform-lib change lands:

1. Uncomment the client block (configmap) and roll the services.
2. Verify lag stays flat (`k8s/monitoring/kafka-lag-rules.yaml`).
3. **Enforce SASL** (breaking): `kubectl -n bhukkad exec redpanda-0 -- rpk
   config set redpanda.enable_sasl true -X brokers=...:9092` — after this,
   unauthenticated plaintext clients are rejected; keep the bootstrap Job's
   creds as the admin identity.

## 7. Nginx 443 secondary router (done by the flag)

`:443` serves the same route set via the shared `bhukkad-routes.conf` include
(`k8s/nginx/configmap.yaml` layout). The **80→443 redirect is commented** in
`bhukkad-api-80.conf` on purpose: the external ingress
(`k8s/ingress.yaml`, letsencrypt-prod) terminates TLS itself and forwards
plaintext — enabling the redirect now would loop it. Uncomment the
`return 308 https://$host$request_uri;` line only when the :80 edge is being
decommissioned in favour of the internal TLS router.

## 8. Rollback

Remove the `components/tls-internal` line and `kubectl apply -k k8s/` — the
base is plaintext again. Note:

* Issued certificates and the CA secret stay in the namespace (delete
  `bhukkad-internal-ca`, the 4 leaf secrets, and the Issuers explicitly if
  you want a clean slate).
* The redpanda `--config` emptyDir and init container disappear with the
  patch; brokers regenerate a plaintext-only config from the base flags.
* Services roll back to `ssl=false` JDBC URLs — no app-side change required
  at any point of either direction.
