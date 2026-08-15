# Configuration Reference

Environment variables, Spring profiles, and feature flags for Bhukkad.

## Spring profiles

| Profile | File | Use case |
|---------|------|----------|
| `dev` (default) | `application-dev.yml` | Local development, Swagger enabled |
| `staging` | `application-staging.yml` | Pre-prod: read replica, quieter SQL logs |
| `prod` | `application-prod.yml` | Production, Swagger disabled, stricter logging |

Activate:

```bash
export SPRING_PROFILES_ACTIVE=dev   # or prod
```

## Core infrastructure

| Variable | Default (dev) | Description |
|----------|---------------|-------------|
| `SERVER_PORT` | `8080` | HTTP port |
| `DB_HOST` | `localhost` | MySQL host |
| `DB_PORT` | `3306` | MySQL port |
| `DB_NAME` | `bhukkad` | Database name |
| `DB_USERNAME` | `root` / `bhukkad_user` | DB user |
| `DB_PASSWORD` | `changeme` / `bhukkad_pass` | DB password |
| `DB_URL` | (constructed) | Full JDBC URL (prod) |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | empty | Redis password (required in prod compose) |

## JWT / security

| Variable | Description |
|----------|-------------|
| `JWT_SECRET` | **Required in prod.** Min 256-bit secret for signing tokens |
| `JWT_EXPIRATION` | Access token TTL ms (dev: 24h, prod: 1h default) |
| `JWT_REFRESH_EXPIRATION` | Refresh token TTL ms |

## Application flags (`app.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `app.debug` | `false` | When `true`, cache admin endpoints are public |
| `app.environment` | `local` | Shown in health responses |
| `app.wallet.allow-direct-top-up` | `false` | Allow `POST /customers/wallet/add-money` without gateway |
| `app.monitoring.prometheus.require-auth` | `true` | Protect `/actuator/prometheus` |
| `app.monitoring.prometheus.bearer-token` | empty | Bearer token for Prometheus scrape |
| `app.delivery.earnings.per-delivery` | `30.0` | Rider earning per completed delivery (₹) |
| `app.scheduled-orders.minimum-lead-minutes` | `30` | Min lead time for scheduled orders |
| `app.scheduled-orders.max-days-ahead` | `7` | Max schedule horizon |
| `app.scheduled-orders.dispatch-interval-ms` | `60000` | Scheduler poll interval |
| `app.settlement.commission-percent` | `15.0` | Platform commission on restaurant settlements |
| `app.settlement.auto-settle-enabled` | `true` | Enable automated daily settlement runs |
| `app.settlement.min-pending-amount` | `100.0` | Min pending ₹ before auto-settling |
| `app.settlement.auto-settle-cron` | `0 0 2 * * *` | Cron for automated settlement |
| `app.delivery-truth.avg-speed-km-per-min` | `0.6` | Rider speed for ETA (~36 km/h) |
| `app.delivery-truth.pickup-buffer-minutes` | `8` | Pickup buffer in ETA |
| `app.delivery-truth.confidence-band-minutes` | `5` | ETA confidence band width |
| `app.delivery-truth.record-snapshots` | `true` | Persist ETA snapshots for analytics |
| `app.inventory.low-stock-threshold` | `10` | Default low-stock threshold for owner alerts |
| `app.events.external.enabled` | `false` | Forward outbox events to external bridge |
| `app.events.external.type` | `log` | Sink type: `log` or `kafka` |
| `app.events.external.kafka.bootstrap-servers` | `localhost:9092` | Kafka/Redpanda brokers |
| `app.events.external.kafka.platform-topic` | `bhukkad.platform.events` | Topic for platform events |
| `app.events.external.kafka.consumer-group` | `bhukkad-platform-consumer` | Consumer group for notifications |
| `app.cache.local.enabled` | `true` | Caffeine L1 cache in front of Redis |
| `app.cache.local.max-size` | `5000` | Max L1 entries |
| `app.cache.local.ttl-seconds` | `60` | L1 TTL |
| `app.geo.redis-geo-enabled` | `true` | Redis GEO for nearby restaurant search |
| `app.inventory.stock-reservation.enabled` | `true` | Redis atomic stock reservation |
| `app.referral.enabled` | `true` | Referral program on registration |

## Read replica (prod)

| Variable | Description |
|----------|-------------|
| `DB_REPLICA_ENABLED` | `true`/`false` |
| `DB_REPLICA_URL` | JDBC URL for read replica |
| `DB_REPLICA_USERNAME` | Falls back to `DB_USERNAME` |
| `DB_REPLICA_PASSWORD` | Falls back to `DB_PASSWORD` |

## Payments (Razorpay)

| Variable | Description |
|----------|-------------|
| `PAYMENT_PROVIDER` | `simulated` or `razorpay` |
| `RAZORPAY_ENABLED` | Enable gateway integration |
| `RAZORPAY_KEY_ID` | Razorpay key |
| `RAZORPAY_KEY_SECRET` | Razorpay secret |
| `RAZORPAY_WEBHOOK_SECRET` | Webhook signature verification |

Webhook URL: `POST /api/v1/payments/webhooks/razorpay`

## Notifications

| Variable | Description |
|----------|-------------|
| `NOTIFICATION_EMAIL_ENABLED` | Send real emails |
| `NOTIFICATION_EMAIL_FROM` | From address |
| `NOTIFICATION_SMS_ENABLED` | Enable SMS |
| `app.notification.sms.provider` | `log` or `twilio` |
| `TWILIO_ACCOUNT_SID` | Twilio SID |
| `TWILIO_AUTH_TOKEN` | Twilio token |
| `TWILIO_FROM_NUMBER` | Twilio sender |
| `app.notification.whatsapp.enabled` | Enable WhatsApp |
| `app.notification.whatsapp.provider` | `log` or `twilio` |
| `TWILIO_WHATSAPP_FROM_NUMBER` | Twilio WhatsApp sender |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka/Redpanda bootstrap (when `type=kafka`) |
| `APP_EVENTS_EXTERNAL_ENABLED` | Enable external event bridge |
| `APP_EVENTS_EXTERNAL_TYPE` | `log` or `kafka` |
| `app.alerting.enabled` | `true` | Enable operational alerting |
| `app.alerting.slow-request.warning-threshold-ms` | `1000` | Slow request warning |
| `app.alerting.slow-request.critical-threshold-ms` | `3000` | Slow request critical alert |
| `ALERT_WEBHOOK_URL` | empty | Optional webhook for alerts |
| `app.notification.push.enabled` | Enable push |
| `app.notification.push.provider` | `log` or `fcm` |
| `FCM_SERVER_KEY` | Firebase legacy server key |

## Live updates / clustering

| Variable | Description |
|----------|-------------|
| `LIVE_RELAY_ENABLED` | Redis pub/sub for multi-instance SSE |
| `LIVE_RELAY_CHANNEL` | Redis channel name |
| `STOMP_BROKER_TYPE` | `simple` or `rabbitmq` |
| `RABBITMQ_HOST` | RabbitMQ host (STOMP) |
| `RABBITMQ_STOMP_PORT` | Default `61613` |
| `EXTERNAL_EVENTS_ENABLED` | Enable external event bridge (`app.events.external.enabled`) |

## S3 menu images

| Variable | Description |
|----------|-------------|
| `S3_ENABLED` | Enable presigned URL uploads |
| `S3_BUCKET` | Bucket name |
| `AWS_REGION` | e.g. `ap-south-1` |

## JVM (`JAVA_OPTS`)

Docker/K8s examples:

```bash
JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+UseStringDeduplication"
```

## Fraud enforcement (`app.fraud.*`)

| Variable | Property | Default | Description |
|----------|----------|---------|-------------|
| `FRAUD_DETECTION_ENABLED` | `app.fraud.enabled` | `true` | Record fraud events at all |
| `FRAUD_BLOCKING_ENABLED` | `app.fraud.blocking-enabled` | `true` | Reject over-threshold attempts with `429`; set `false` to observe only |
| — | `app.fraud.window-minutes` | `60` | Sliding velocity window |
| — | `app.fraud.retry-after-seconds` | `300` | Value sent in the `Retry-After` header |
| — | `app.fraud.default-threshold` | `20` | Used for event types with no explicit threshold |
| — | `app.fraud.thresholds.<event>.per-ip` / `.per-device` | see below | Per-event-type limits; `0` disables that dimension |

Shipped thresholds: `auth-register` 10 per IP / 5 per device, `auth-login` 40 / 25, `order-create` 25 / 15.

## Delivery proof (`app.delivery.proof.*`)

| Variable | Default | Description |
|----------|---------|-------------|
| `DELIVERY_PROOF_ENABLED` | `true` | Allow agents to issue and verify handover proof |
| `DELIVERY_PROOF_ENFORCED` | `false` | Block `delivered` until proof is verified; ships off so in-flight orders are not stranded |
| `DELIVERY_PROOF_OTP_EXPIRY_MINUTES` | `10` | OTP validity |
| `DELIVERY_PROOF_MAX_OTP_ATTEMPTS` | `5` | Attempts before the proof fails |
| `DELIVERY_PROOF_OTP_RESEND_COOLDOWN_SECONDS` | `60` | Minimum gap between OTP issues |

See [trust-and-compliance.md](./trust-and-compliance.md) for the rollout order of both flags.

## Cache TTLs (`cache.ttl.*`)

Configured in `application.yml` (seconds). Examples:

- `restaurant`: 1800
- `menu-item`: 900
- `order`: 300
- `kitchen-queue`: 15
- `home-feed`: 60
- `serviceability`: 60

## Rate limits (`app.rate-limit.buckets.*`)

| Bucket | Default limit | Window |
|--------|---------------|--------|
| `auth-login` | 10 | 60s |
| `search` | 30 | 60s |
| `cart-mutation` | 40 | 60s |
| `order-track` | 20 | 60s |

## Actuator endpoints

| Profile | Exposed |
|---------|---------|
| dev | `health`, `info` |
| prod | `health`, `info`, `metrics`, `prometheus` |

Health probes (K8s):

- Liveness: `/actuator/health/liveness`
- Readiness: `/actuator/health/readiness`

## Dev vs prod summary

| Setting | Dev | Staging | Prod |
|---------|-----|---------|------|
| Swagger | Enabled | Disabled | Disabled |
| SQL logging | On | WARN only | Off |
| Stack traces in errors | Shown | Hidden | Hidden |
| Direct wallet top-up | Allowed | Blocked | Blocked (use Razorpay) |
| Read replica | Off | On (default) | Configurable |
| Prometheus auth | Off | On | On (ADMIN or bearer) |
| JWT TTL | Longer | Shorter | Shorter |

## Example `.env` for Docker prod

```env
MYSQL_DATABASE=bhukkad
MYSQL_ROOT_PASSWORD=<strong>
MYSQL_USER=bhukkad_user
MYSQL_PASSWORD=<strong>
REDIS_PASSWORD=<strong>
JWT_SECRET=<256-bit-hex>
RAZORPAY_ENABLED=true
RAZORPAY_KEY_ID=rzp_live_xxx
RAZORPAY_KEY_SECRET=xxx
RAZORPAY_WEBHOOK_SECRET=xxx
PROMETHEUS_BEARER_TOKEN=<random>
```
