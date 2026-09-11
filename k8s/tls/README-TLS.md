# In-cluster TLS (flag-gated)

**Status:** plumbing present, **DEFAULT OFF**. `TLS_ENABLED=false` in
`k8s/configmap.yaml` means the entire stack behaves EXACTLY as it did pre-TLS:
postgres runs plain TCP, redpanda serves the single `PLAINTEXT` listener,
redis listens on `6379/requirepass`, and every client URL keeps
`sslmode=disable` (JDBC) / plaintext (Kafka/Lettuce). **Do not flip until the
whole fleet + cert-manager are ready and you have a maintenance window.**

## The gate pattern (how "zero change until flipped" is enforced)

Every TLS consumer is a shell wrapper that branches on `TLS_ENABLED`, mounting
the cert Secret as `optional: true` so even when the Secret does not exist the
containers start unchanged:

| Component | Off (default) | `TLS_ENABLED=true` |
|---|---|---|
| postgres (`k8s/postgres/deployment.yaml`) | `exec docker-entrypoint.sh postgres -c config_file=…` (byte-identical legacy argv) | appends `-c ssl=on -c ssl_cert_file/-key_file/-ca_file=/etc/postgresql/tls/*` |
| redis (`k8s/redis/deployment.yaml`) | `redis-server … --requirepass …` (legacy argv) | appends `--tls-port 6380 --tls-cert-file … --tls-auth-clients no`; plain `6379` kept too |
| redpanda (`k8s/components/redpanda/statefulset.yaml`) | `--kafka-addr=PLAINTEXT://0.0.0.0:9092` only | TLS/SASL listener block (currently COMMENTED — see step 5) |
| apps (JDBC) | `DB_URL … ssl=false` | `DB_JDBC_SSLMODE=disable` placeholder + CA truststore (step 4 edits URLs) |

cert-manager `Issuer`/`Certificate` CRDs live in a SEPARATE
`k8s/tls/kustomization` that is **not** in the base — so a cert-manager-less
cluster never tries to resolve them (see `k8s/tls/kustomization.yaml`).

## Prerequisites

* cert-manager installed (cluster-scoped; `docs/istio-install.md`-style ops
  step). Leaf CRDs would no-op if applied without it — and would BREAK
  `kubectl apply -k k8s/` if the CRDs were missing, which is exactly why they
  are NOT in the base.
* A maintenance window. Redeploying postgres/redpanda/redis interrupts their
  clients.

## Flip order (STAGED — clients before servers)

Do it in this order. The principle: **make every client TLS-capable and
trust-the-CA FIRST, then turn the servers on, then tighten URLs to
verify-full.** The servers here speak TLS alongside their plaintext port, so
an intermediate rollout is safe.

1. **Back up first.** Take a fresh wal-g base backup + dump set — a failed TLS
   cutover is a restore scenario
   (`k8s/docs/RUNBOOK-PITR.md`, `docs/backup-and-restore-runbook.md`).

2. **Provision the CA + leaves:**
   ```bash
   kubectl apply -k k8s/tls/          # CA Secret bhukkad-ca + 3 leaf Secrets
   kubectl -n bhukkad get certificate # all Ready=True before continuing
   ```

3. **Distribute trust WITHOUT enabling (`TLS_ENABLED` still false):** the
   server-side wrappers + optional cert mounts are already committed; apply,
   roll pods, and confirm byte-identical plaintext behavior (this step only
   validates the optional Secret mounts resolve once `k8s/tls/` exists):
   ```bash
   kubectl apply -k k8s/
   kubectl -n bhukkad rollout restart deployment/bhukkad-postgresql deployment/bhukkad-redis
   ```

4. **Clients trust the CA + TLS URLs ready.** Export the CA and wire each
   client (these app-side edits are OUT OF SCOPE for the ops batch — recorded
   as coordination in the PR):
   * Postgres (JDBC): change `DB_URL`/`DB_REPLICA_URL` from
     `ssl=false` to `sslmode=verify-full&sslrootcert=/etc/pg-ca/ca.crt`;
     replace `DB_JDBC_SSLMODE=disable` mount with a per-app CA volume so
     `verify-full` can validate `bhukkad-postgresql.bhukkad.svc.cluster.local`.
   * Redpanda (Spring Kafka): add `ssl.truststore` config + change bootstrap
     to the `SSL` listener once step 5 publishes it.
   * Redis (Lettuce): enable `spring.data.redis.ssl.*` with the CA.

5. **Turn servers on: set `TLS_ENABLED=true` in `k8s/configmap.yaml`** (or a
   targeted overlay per component for a canary), commit, redeploy:
   ```bash
   kubectl apply -k k8s/
   kubectl -n bhukkad rollout restart deployment/bhukkad-postgresql deployment/bhukkad-redis
   # for redpanda, ALSO uncomment the TLS/SASL listener block below first:
   ```
   The redpanda flip is NOT just a comment-removal — redpanda v23.3 configures
   listener TLS via node-config, so follow the step-by-step placeholder notes
   in the commented `TLS + SASL` block of
   `k8s/components/redpanda/statefulset.yaml` (SSL listener addr line, cert
   Secret mount + per-listener `tls:` config, SASL superuser bootstrap with
   CHANGE_ME credentials until ops provisions them via Vault). Re-verify
   flag/node-config syntax against the pinned image's `--help` at flip.

6. **Verify per component** (see below), then remove the plaintext ports/URL
   options from clients and re-roll so `verify-full` is actually enforced by
   every path.

## Validation (after each flip)

```bash
# Postgres: TLS handshake + negotiated protocol.
psql "host=bhukkad-postgresql user=… sslmode=verify-full sslrootcert=./bhukkad-ca.crt dbname=postgres" \
     -c "SELECT pid, ssl, version, cipher FROM pg_stat_ssl WHERE pid = pg_backend_pid();"
# expect ssl=t and TLSv1.2/1.3

# Redis (uses 6380 when on):
redis-cli --tls --cacert ./bhukkad-ca.crt -h bhukkad-redis -p 6380 PING   # → PONG

# Kafka/Redpanda SSL listener (once step 5):
rpk cluster health -X brokers …:9093 -X sasl.enabled … -X security.protocol=SSL
```

## Rollback (fast — one flag)

`TLS_ENABLED` is the ONLY content-plane switch (plus the client URL edits):

```bash
kubectl -n bhukkad patch configmap bhukkad-config \
  --patch '{"data":{"TLS_ENABLED":"false"}}'
kubectl -n bhukkad rollout restart deployment/bhukkad-postgresql deployment/bhukkad-redis
```

   Wrappers return to the byte-identical plaintext argv; the leaf Secrets can stay
   (harmless, mounted optional). If the redpanda SSL listener/config was added
   in step 5, revert statefulset.yaml to the committed PLAINTEXT-only args and
   redeploy. **Apps that you already migrated to
`sslmode=verify-full` (step 4) must be rolled back to `disable`/`prefer` too,
or they will fail to connect** — keep a configmap snapshot before the flip so
both server and client configs roll back together. cert-manager objects
themselves never touch plaintext traffic, so they need no rollback (leave them
provisioned; renew is automatic).

## Files in this batch

* `k8s/tls/issuer.yaml` — self-signed bootstrap issuer + cluster CA
  (`bhukkad-ca`) + CA issuer.
* `k8s/tls/certificates.yaml` — leaf Certs for postgres / redpanda (3-broker
  wildcard) / redis → Secrets `*-tls`.
* `k8s/tls/kustomization.yaml` — standalone (NOT in base).
* `k8s/postgres/deployment.yaml`, `k8s/redis/deployment.yaml` — `TLS_ENABLED`
  wrapper + optional cert volume.
* `k8s/configmap.yaml` — `TLS_ENABLED=false`, `DB_JDBC_SSLMODE=disable`
  placeholder.
