# Configuration Reference

Environment variables, Spring profiles, and feature flags for Bhukkad.

## Spring profiles

| Profile | File | Use case |
|---------|------|----------|
| `dev` (default) | `application-dev.yml` | Local development, Swagger enabled |
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

## Cache TTLs (`cache.ttl.*`)

Configured in `application.yml` (seconds). Examples:

- `restaurant`: 1800
- `menu-item`: 900
- `order`: 300
- `kitchen-queue`: 15

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

| Setting | Dev | Prod |
|---------|-----|------|
| Swagger | Enabled | Disabled |
| SQL logging | On | Off |
| Stack traces in errors | Shown | Hidden |
| Direct wallet top-up | Allowed | Blocked (use Razorpay) |
| Prometheus auth | Off | On (ADMIN or bearer) |
| JWT TTL | Longer | Shorter |

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
