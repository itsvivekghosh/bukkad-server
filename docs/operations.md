# Operations & Troubleshooting

Day-2 operations: testing, logging, metrics, database migrations, and common fixes.

## Build & test

```bash
# Full test suite
mvn clean test

# Skip tests (faster package)
mvn clean package -DskipTests

# Single test class
mvn test -Dtest=OrderServiceImplTest

# Compile only
mvn compile -q
```

**CI expectation:** all tests pass (~700+). Tests use Mockito; no live DB required.

## Logs

Single log file:

| File | Contents |
|------|----------|
| `logs/app.log` | All application output (errors, orders, payments, security, alerts) |

Console output is enabled in `dev` only. Production writes to `logs/app.log` only.

Configuration: `src/main/resources/logback-spring.xml`

### Request tracing

Every API response includes:

| Header | Description |
|--------|-------------|
| `X-Trace-Id` | 16-char distributed trace ID (propagated from inbound `X-Trace-Id`, `X-Correlation-Id`, or W3C `traceparent`) |
| `X-Request-Id` | 8-char per-request ID (propagated from `X-Request-Id`) |
| `X-Timestamp` | Response timestamp (UTC) |

`ApiResponse` JSON bodies also include `traceId` and `requestId` fields for client-side correlation.

Log patterns include trace and request IDs via MDC in `logs/app.log`.

```bash
# Find all logs for a trace
grep "abcd1234abcd1234" logs/app.log

# Check response headers
curl -si http://localhost:8080/api/v1/health/ping | grep -i x-trace
```

### Alerting

Operational alerts use logger `ALERT` and are written to `logs/app.log` for:

- Slow requests (warning ≥1s, critical ≥3s)
- HTTP 4xx/5xx responses
- Unhandled exceptions
- Auth failures (401/403)

Optional webhook (Slack/Discord/PagerDuty-compatible JSON POST):

```yaml
app.alerting.webhook.enabled: true
app.alerting.webhook.url: https://hooks.example.com/alert
```

Configure thresholds under `app.alerting.*` in `application.yml`.

### Docker

```bash
docker logs -f bhukkad-app-dev
docker logs bhukkad-mysql-dev --tail 50
```

### Kubernetes

```bash
kubectl logs -n bhukkad -l component=api -f --tail=200
kubectl logs -n bhukkad <pod-name> -c bhukkad-app --previous
```

## Health & readiness

| Endpoint | Use |
|----------|-----|
| `GET /api/v1/health/ping` | Fast liveness |
| `GET /api/v1/health` | App metadata |
| `GET /api/v1/health/detailed` | DB + Redis + JVM |
| `GET /actuator/health` | Spring Actuator aggregate |
| `GET /actuator/health/liveness` | K8s liveness |
| `GET /actuator/health/readiness` | K8s readiness |

```bash
curl -s http://localhost:8080/api/v1/health/detailed | jq .
```

## Metrics (Prometheus)

Prod exposes `/actuator/prometheus`.

- **Dev:** open access when `app.monitoring.prometheus.require-auth=false`
- **Prod:** requires `ADMIN` JWT or `Authorization: Bearer $PROMETHEUS_BEARER_TOKEN`

Custom metrics include order counters (`OrderMetrics`).

## Database migrations (Flyway)

Migration files live in `src/main/resources/db/migration/`:

- `V1__baseline_schema.sql` — consolidated baseline schema
- `V2__platform_operations.sql` — platform/operations tables
- `V27__api_keys.sql` — partner API key management
- `V28__missing_entity_tables.sql` — entity/summary tables dropped in the V1 consolidation

See [db/migration/README.md](../src/main/resources/db/migration/README.md) for details.

**Rules:**

- Never edit an already-applied migration (e.g. `V1`, `V2`, `V27`, `V28`) after it ships
  to shared environments — add the next version file (`V29__your_change.sql`) instead
- New migration files must be idempotent (`CREATE TABLE IF NOT EXISTS`, guarded
  `ALTER TABLE`) so they are safe on both fresh and existing databases
- App uses `ddl-auto: none` — Hibernate never alters the schema; Flyway owns it
- Existing databases with pre-consolidation history are handled by
  `ignore-migration-patterns: "*:missing"`; run `flyway-repair` after any checksum
  change (see CI `FLYWAY_REPAIR_ON_DEPLOY`)

### Check migration status

Inspect app startup logs for Flyway lines, or connect to MySQL:

```sql
SELECT * FROM flyway_schema_history ORDER BY installed_rank;
```

### Failed migration

1. Fix SQL in a new versioned file (or repair dev DB)
2. For dev: `DROP DATABASE bhukkad;` and restart
3. For prod: use Flyway repair CLI or manual DBA intervention — never drop prod

## Cache administration

```http
GET  /api/v1/cache/stats
DELETE /api/v1/cache/clear/{cacheName}
```

Requires `ADMIN` role (or `app.debug=true` in dev).

## Redis

```bash
# Local
redis-cli ping

# Docker
docker exec -it bhukkad-redis-dev redis-cli ping

# K8s
kubectl exec -n bhukkad deploy/bhukkad-redis -- redis-cli ping
```

## Order live updates debugging

1. Confirm Redis is up (live relay uses pub/sub across instances)
2. Test SSE: `curl -N -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/orders/stream/customer/1`
3. Check `app.cluster.live-relay.enabled=true`

## Payment testing

**Simulated (default):** payments complete in-process without external gateway.

**Razorpay:**

1. Set `RAZORPAY_ENABLED=true` and keys
2. Create order → `GET /api/v1/payments/orders/{orderId}` for `gatewayOrderId`
3. Complete payment in Razorpay test mode or send webhook to `/api/v1/payments/webhooks/razorpay`

## Security incidents

| Issue | Action |
|-------|--------|
| JWT secret leaked | Rotate `JWT_SECRET`, force re-login |
| K8s secrets committed | Rotate all credentials in `k8s/secrets.yaml` |
| Prometheus exposed | Enable `require-auth` + bearer token |

## Performance tuning

| Layer | Knob |
|-------|------|
| JVM | `JAVA_OPTS`, `MaxRAMPercentage` |
| HikariCP | `spring.datasource.hikari.maximum-pool-size` |
| Redis | `cache.ttl.*`, connection pool in prod yml |
| MySQL | `innodb_buffer_pool_size` in `docker/mysql/my.cnf` or k8s configmap |
| K8s HPA | `k8s/app/hpa.yaml` CPU/memory targets |

## Useful scripts

| Script | Purpose |
|--------|---------|
| `docker/scripts/deploy.sh` | Compose deploy |
| `docker/scripts/health_check.sh` | Health verification |
| `docker/scripts/swagger-test.sh` | Quick HTTP smoke test |
| `docker/scripts/stop.sh` | Stop compose stack |
| `k8s/scripts/deploy.sh` | K8s deploy |
| `k8s/scripts/status.sh` | Cluster status |
| `k8s/scripts/destroy.sh` | Tear down k8s |

## Common errors

### `401 Unauthorized`

- Missing or expired JWT
- Wrong role for endpoint (e.g. customer hitting owner API)

### `403 Forbidden`

- Valid token but insufficient role
- Prometheus without auth in prod

### `429 Too Many Requests`

- Rate limit exceeded — back off and retry

### `OptimisticLockingFailureException`

- Concurrent order update — client should retry

### `Cart contains items from a different restaurant`

- Legacy message; multi-restaurant carts are supported — ensure `restaurantId` in order matches items being checked out

### Flyway `Validate failed`

- Database schema doesn't match migrations — align DB or add migration

## On-call quick commands

```bash
# Is the app up?
curl -fsS http://localhost:8080/api/v1/health/ping

# Docker stack
docker compose -f docker/docker-compose.dev.yml ps

# K8s
kubectl get pods -n bhukkad
kubectl rollout status deployment/bhukkad-app -n bhukkad

# Recent errors
kubectl logs -n bhukkad -l component=api --tail=100 | grep -i error
```

## Getting help

1. Check logs and health/detailed endpoint
2. Reproduce with Swagger or curl
3. Run `mvn test` to confirm codebase health
4. See [configuration.md](./configuration.md) for env var mismatches
