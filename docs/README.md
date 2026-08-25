# Bhukkad Backend Documentation

## Documentation map

| Document | Description |
|----------|-------------|
| [getting-started.md](./getting-started.md) | Prerequisites, first run, local dev |
| [docker.md](./docker.md) | Docker Compose, scripts, health checks |
| [kubernetes.md](./kubernetes.md) | Minikube/cluster deploy |
| [configuration.md](./configuration.md) | Environment variables, profiles, secrets |
| [api-usage.md](./api-usage.md) | Auth, roles, endpoints, curl examples |
| [technical-manual.md](./technical-manual.md) | **Consolidated technical API manual** — every endpoint: purpose, params, response schemas (2xx/4xx/5xx), behavioral analysis |
| [api/README.md](./api/README.md) | **Frontend integration guide** — conventions, auth, errors, SSE, idempotency |
| [api/auth.md](./api/auth.md) | Auth endpoints (register/login/MFA/tokens/passwords) |
| [api/account.md](./api/account.md) | Profile, addresses, wallet, loyalty, referrals, subscriptions |
| [api/discovery.md](./api/discovery.md) | Home feed, search, restaurants, menu, coupons |
| [api/orders.md](./api/orders.md) | Cart, order create/track/cancel, delivery actions, SSE, group/gift |
| [api/payments.md](./api/payments.md) | Payment status, webhooks, refunds, disputes |
| [api/delivery.md](./api/delivery.md) | Rider app: earnings, availability, location, batches |
| [api/reviews.md](./api/reviews.md) | Order reviews, menu-item ratings |
| [api/admin.md](./api/admin.md) | Admin dashboards, users, feature flags, DLQ, fraud |
| [features.md](./features.md) | Platform features V10–V17 (consolidated) |
| [operations.md](./operations.md) | Logs, metrics, migrations, troubleshooting |
| [testing.md](./testing.md) | Unit/regression tests, coverage, CI |
| [DEPLOYMENT.md](./DEPLOYMENT.md) | CI/CD, EC2 deploy, secrets checklist |

## Quick links

| Resource | URL |
|----------|-----|
| API base | `http://localhost:8080/api/v1` |
| Swagger | `http://localhost:8080/swagger-ui.html` |
| Health | `http://localhost:8080/api/v1/health/ping` |

## Audience guide

| Role | Start here |
|------|------------|
| New developer | [getting-started.md](./getting-started.md) → [api-usage.md](./api-usage.md) |
| DevOps | [DEPLOYMENT.md](./DEPLOYMENT.md) → [operations.md](./operations.md) |
| On-call | [operations.md](./operations.md) |

All REST endpoints use the `/api/v1` prefix.
