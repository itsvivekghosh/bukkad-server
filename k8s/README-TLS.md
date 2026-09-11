# In-cluster TLS — cert-manager internal CA, flag-gated (P2)

In-cluster TLS for the bhukkad stack, gated behind **one kustomize line** and
a **config flag**, dev-boot-compatible by default.

```
FLAG 1 (server side): k8s/kustomization.yaml
    components:
      - components/redpanda
      - components/kafka-exporter
      - components/tls          # ← UNCOMMENT to deploy TLS infrastructure
FLAG 2 (client side): k8s/configmap.yaml
    DB_SSL_ENABLED: "false"     # ← flip to "true" at stage A/B (below)
```

What `components/tls` ships when enabled (requires **cert-manager** in the
cluster — applying without its CRDs fails on the Issuer/Certificate CRs):

| Target | Server side | Client side (flag / commented-ready) |
|---|---|---|
| **postgres** | `ssl=on` via `include_if_exists` conf (`bhukkad-postgres-tls-conf` CM, mounted by the patch), cert `bhukkad-postgres-tls` | `DB_SSL_ENABLED` in `bhukkad-config`; JDBC URLs move to `sslmode=require|verify-full` (stages A/B below) |
| **redpanda** | second listener `TLS://…:9093` + `redpanda.tls[0]` from the wildcard `bhukkad-redpanda-tls` secret (one secret, all 3 brokers); PLAINTEXT 9092 stays up | commented `KAFKA_*` env block in `bhukkad-config` (needs platform-lib SSL props — coordination) |
| **redis** | `--tls-port 6380` + cert `bhukkad-redis-tls`; plaintext 6379 stays up | commented `SPRING_DATA_REDIS_SSL_ENABLED`/`_PORT` block in `bhukkad-config` |
| **nginx** | 443 secondary router via `/etc/nginx/conf.d/tls-server.conf` drop-in (cert `bhukkad-nginx-tls`); 80 stays up | none (edge clients cut over directly) |
| **redpanda SASL** | `redpanda-sasl-bootstrap` Job creates the SCRAM superuser from `bhukkad-secrets: REDPANDA_SUPERUSER_PASSWORD` | enforcement flags commented in the redpanda patch (step 3) |

Everything here is in-cluster (cert-manager CA `bhukkad-internal-ca`, 10y;
leaves auto-renewed by cert-manager). The public edge keeps whatever
platform TLS/ingress termination it already has — `k8s/ingress.yaml` and the
nginx 443 router are independent of it.

## Flip order

Execute in order; each step is verifiable and reversible. Never enable
client-side verification before the server side is confirmed serving TLS.

### Step 0 — prerequisites

```bash
kubectl get crd issuers.cert-manager.io certificates.cert-manager.io   # cert-manager present?
kubectl -n bhukkad get secret bhukkad-secrets \
  -o jsonpath='{.data.REDPANDA_SUPERUSER_PASSWORD}' | base64 -d   # set in Vault (bhukkad/prod/redpanda)
```

### Step 1 — deploy server-side TLS

Uncomment `- components/tls` in `k8s/kustomization.yaml`, build, apply:

```bash
kubectl kustomize k8s | kubectl apply -f -
kubectl -n bhukkad get certificate            # all READY=True
kubectl -n bhukkad get secret bhukkad-postgres-tls bhukkad-redis-tls \
  bhukkad-redpanda-tls bhukkad-nginx-tls bhukkad-internal-ca
```

Verify each server speaks TLS while **plaintext keeps working** (nothing
client-visible changed yet):

```bash
kubectl -n bhukkad exec deploy/bhukkad-postgresql -c postgres -- \
  psql -U "$POSTGRES_USER" -d postgres -tAc "show ssl;"           # expect: on
kubectl -n bhukkad exec deploy/bhukkad-redis -- \
  redis-cli --tls --cacert /etc/redis/tls/ca.crt -p 6380 --no-auth-warning \
    -a "$REDIS_PASSWORD" ping | grep PONG
kubectl -n bhukkad get job redpanda-sasl-bootstrap -o jsonpath='{.status.succeeded}'  # 1
```

### Step 2 — postgres clients, stage A (encrypt, no verification)

1. Update the JDBC URLs: `DB_URL` / `DB_REPLICA_URL` in `bhukkad-config`
   **and every per-service `*_DB_URL` in Vault** (`bhukkad/prod/database`,
   keys like `order_db_url`) — append
   `?sslmode=require` (or `&sslmode=require` if query params exist).
2. Set `DB_SSL_ENABLED: "true"` in `k8s/configmap.yaml` (documented marker
   for the flip; the URLs carry the actual behavior).
3. Rolling-restart the fleet:
   `kubectl -n bhukkad rollout restart deploy -l app=bhukkad`
   (per-service DB URLs come from the Vault-backed `bhukkad-secrets`, which
   the ExternalSecret refreshes within 1h — force with
   `kubectl -n bhukkad annotate externalsecret bhukkad-secrets force-sync=$(date +%s) --overwrite`).

### Step 3 — postgres clients, stage B (verify-full, optional but recommended)

Mount the CA into client pods and switch to `sslmode=verify-full`:

- mount secret `bhukkad-internal-ca` key `ca.crt` at `/etc/postgresql-ca/tls.crt`
  in the client Deployments (add per-service patch or extend the platform base —
  ops decision; NOT wired by default to keep the fleet diff small),
- change URLs to
  `?currentSchema=public&sslmode=verify-full&sslrootcert=/etc/postgresql-ca/tls.crt`,
- restart as in step 2.

The commented `DB_URL` / `DB_REPLICA_URL` variants in `k8s/configmap.yaml`
show the exact final strings.

### Step 4 — redpanda TLS → SASL

1. Create the SCRAM superuser (the bootstrap Job from step 1):
   `kubectl -n bhukkad logs job/redpanda-sasl-bootstrap`.
2. Enable SASL enforcement: in
   `k8s/components/tls/patches/redpanda-statefulset.yaml` uncomment
   `--set redpanda.enable_sasl=true` and
   `--set redpanda.superusers=[bhukkad-admin]`, re-apply, wait for the
   StatefulSet rollout.
3. Clients (kafka-exporter + Spring fleet): the client env blocks in
   `k8s/configmap.yaml` are **commented-ready** — but the Spring side needs
   platform-lib `KafkaProperties`/`KafkaPlatformConfig` SSL/SASL property
   support first (**coordination note — services-side change, not done in
   this batch**). The kafka-exporter's `--tls.*`/`--sasl.*` args in
   `k8s/components/kafka-exporter/deployment.yaml` are commented-ready too.
4. Finally, per broker: make `TLS://` the primary advertised listener
   (re-point `KAFKA_BOOTSTRAP_SERVERS` to `:9093`), then remove the
   PLAINTEXT listener args. Verify with
   `rpk cluster health -X tls.enabled -X brokers redpanda-0...:9093`.

### Step 5 — redis clients

After confirming 6380 answers TLS (step 1), uncomment in `k8s/configmap.yaml`:

```yaml
SPRING_DATA_REDIS_PORT: "6380"
SPRING_DATA_REDIS_SSL_ENABLED: "true"    # spring.data.redis.ssl.enabled (Boot 3.2)
```

Restart the fleet, then close plaintext: in
`k8s/components/tls/patches/redis-deployment.yaml` change `--port 6379` →
`--port 0` (sentinel config in `k8s/redis/redis-sentinel.yaml` also needs the
TLS port — verify sentinel connectivity before this final step).

### Step 6 — nginx 80 → 443

After edge clients are confirmed on HTTPS (via the LoadBalancer :443 or the
ingress), flip the base `k8s/nginx/configmap.yaml` port-80 server block from
proxying to a redirect:

```nginx
server {
    listen 80;
    server_name _;
    return 301 https://$host$request_uri;
}
```

Keep `/api/v1/health` on :80 if your LB health checks hit HTTP.

## Dev compatibility

None of this touches the dev path: `services/docker/docker-compose.dev.yml`
runs plaintext postgres/redis/redpanda, the base kustomize build (flag off)
renders zero TLS resources, and `DB_SSL_ENABLED` defaults to `false`. The
base `postgresql.conf` ships only the inert
`include_if_exists = '/etc/postgresql/conf.d/postgresql-tls.conf'` line.

## Rollback

- Client side: set the URLs/flags back (`ssl=false`, ports back), restart.
- Server side: remove the `components/tls` line, re-apply; the certs/issuers
  remain as orphaned objects (harmless) — delete with
  `kubectl -n bhukkad delete certificate,certmanager.clusterissuer -l app=bhukkad`
  plus the two `ClusterIssuer` names if desired.
