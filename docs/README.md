# Bhukkad Backend Documentation

Welcome to the Bhukkad food-delivery backend. This folder contains onboarding and operational guides for developers, QA, and DevOps.

## Who should read what?

| Audience | Start here | Then read |
|----------|------------|-----------|
| New developer | [Getting Started](./getting-started.md) | [Local Development](./local-development.md) → [API Usage](./api-usage.md) |
| Mobile / frontend engineer | [API Usage](./api-usage.md) | [Configuration](./configuration.md) |
| DevOps / SRE | [Docker](./docker.md) | [Kubernetes](./kubernetes.md) → [Operations](./operations.md) |
| On-call engineer | [Operations](./operations.md) | [Configuration](./configuration.md) |

## Documentation map

| Document | Description |
|----------|-------------|
| [getting-started.md](./getting-started.md) | Prerequisites, repo layout, first run in 15 minutes |
| [local-development.md](./local-development.md) | Run with Maven + local MySQL/Redis (no Docker) |
| [docker.md](./docker.md) | Docker Compose dev/prod, scripts, health checks |
| [kubernetes.md](./kubernetes.md) | Minikube/cluster deploy, scaling, ingress |
| [configuration.md](./configuration.md) | Environment variables, profiles, secrets, feature flags |
| [api-usage.md](./api-usage.md) | Auth flow, roles, endpoints, curl examples, SSE |
| [operations.md](./operations.md) | Tests, logs, metrics, migrations, troubleshooting |

## Quick links (running stack)

| Resource | Dev URL |
|----------|---------|
| API base | `http://localhost:8080/api/v1` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| Health ping | `http://localhost:8080/api/v1/health/ping` |
| Actuator (dev) | `http://localhost:8080/actuator/health` |

## API versioning

All REST endpoints use the **`/api/v1`** prefix. Legacy `/api/**` paths are automatically rewritten to `/api/v1/**` for backward compatibility.

## Support checklist for new joiners

1. Clone repo and install JDK 17, Maven 3.9+, Docker (optional).
2. Start stack: `./docker/scripts/deploy.sh` **or** follow [local-development.md](./local-development.md).
3. Open Swagger and register a `CUSTOMER` user.
4. Run tests: `mvn clean test`.
5. Skim [api-usage.md](./api-usage.md) for auth + order flow.
