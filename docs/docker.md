# Docker Guide

Run the full Bhukkad stack with Docker Compose and the optimized multi-stage Dockerfile.

## Architecture (Compose)

```
┌─────────────┐     ┌─────────────┐     ┌──────────────────┐
│   nginx     │────▶│  bhukkad-app │────▶│  MySQL 8.0       │
│  (prod only)│     │  Spring Boot │     └──────────────────┘
└─────────────┘     │              │────▶┌──────────────────┐
                    └──────────────┘     │  Redis 7         │
                                         └──────────────────┘
```

**Dev** exposes the app on port `8080` directly. **Prod** puts nginx in front on port `80`.

## Files

| Path | Purpose |
|------|---------|
| `docker/Dockerfile` | Multi-stage build (Maven + Spring Boot layers + JRE) |
| `docker/docker-compose.dev.yml` | Dev: app + MySQL + Redis + optional tools |
| `docker/docker-compose.prod.yml` | Prod: app + MySQL + Redis + nginx |
| `docker/scripts/deploy.sh` | One-command deploy script |
| `docker/scripts/entrypoint.sh` | JVM launcher (optional TCP wait) |
| `docker/mysql/my.cnf` | MySQL performance tuning |
| `docker/redis/redis.conf` | Redis dev config |
| `docker/redis/redis-prod.conf` | Redis prod config |

## Quick start (development)

```bash
# From repo root
./docker/scripts/deploy.sh
```

Options:

```bash
./docker/scripts/deploy.sh --no-tools      # Skip Redis Commander & phpMyAdmin
./docker/scripts/deploy.sh --skip-build    # Reuse existing image
./docker/scripts/deploy.sh --clean         # Remove volumes and start fresh
```

### Dev endpoints

| Service | URL |
|---------|-----|
| API | http://localhost:8080 |
| Health | http://localhost:8080/api/v1/health/ping |
| Swagger | http://localhost:8080/swagger-ui.html |
| Redis Commander | http://localhost:8081 (profile `tools`) |
| phpMyAdmin | http://localhost:8082 (profile `tools`) |

### Manual Compose commands

```bash
cd docker

# Start infrastructure only
docker compose -f docker-compose.dev.yml up -d mysql redis

# Build and start app
export DOCKER_BUILDKIT=1
docker compose -f docker-compose.dev.yml build app
docker compose -f docker-compose.dev.yml up -d app

# With dev tools
docker compose -f docker-compose.dev.yml --profile tools up -d

# Follow logs
docker logs -f bhukkad-app-dev

# Stop
docker compose -f docker-compose.dev.yml down

# Stop and delete data
docker compose -f docker-compose.dev.yml down -v
```

## Production Compose

Create `docker/.env.prod` (never commit real secrets):

```env
MYSQL_DATABASE=bhukkad
MYSQL_ROOT_PASSWORD=change-me-root
MYSQL_USER=bhukkad_user
MYSQL_PASSWORD=change-me-user
REDIS_PASSWORD=change-me-redis
JWT_SECRET=your-256-bit-secret-here
```

Deploy:

```bash
./docker/scripts/deploy.sh --prod
# or:
cd docker
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
```

Prod binds MySQL/Redis to `127.0.0.1` only; nginx listens on port `80`.

## Dockerfile highlights

- **BuildKit Maven cache** — faster rebuilds after code changes
- **Spring Boot layertools** — smaller layer diffs on deploy
- **Slim JRE image** — no MySQL/Redis clients in runtime
- **Non-root user** `bhukkad` (uid 1001)
- **Health check** — `curl` against `/api/v1/health/ping`

Build manually:

```bash
export DOCKER_BUILDKIT=1
docker build -f docker/Dockerfile -t bhukkad-server:latest .
```

## Container environment

Key variables passed to the app container:

| Variable | Dev default | Description |
|----------|-------------|-------------|
| `SPRING_PROFILES_ACTIVE` | `dev` | Spring profile |
| `WAIT_FOR_SERVICES` | `false` | TCP wait before JVM (compose uses `depends_on` instead) |
| `DB_HOST` | `mysql` | MySQL hostname |
| `REDIS_HOST` | `redis` | Redis hostname |
| `JAVA_OPTS` | G1GC, container support | JVM flags |

## Health checks

```bash
# Script
./docker/scripts/health_check.sh

# Manual
curl -fsS http://localhost:8080/api/v1/health/ping
curl -fsS http://localhost:8080/api/v1/health/detailed | jq .
```

## Rebuild after code changes

```bash
mvn clean package -DskipTests
cd docker
DOCKER_BUILDKIT=1 docker compose -f docker-compose.dev.yml build app
docker compose -f docker-compose.dev.yml up -d app
docker logs -f bhukkad-app-dev
```

Or use `./docker/scripts/deploy.sh --skip-build` only if the image is already current.

## Resource limits (Compose)

Dev/prod compose files set CPU/memory limits and reservations. Adjust in `docker-compose.*.yml` under `deploy.resources` for your host.

## Troubleshooting

| Issue | Solution |
|-------|----------|
| App exits immediately | `docker logs bhukkad-app-dev` — check DB/Redis connectivity |
| MySQL unhealthy | Wait 60s on first start; check `docker logs bhukkad-mysql-dev` |
| Port conflict on 3306/6379/8080 | Stop local MySQL/Redis or change port mappings |
| Stale schema | `docker compose -f docker-compose.dev.yml down -v` and redeploy |
| Build slow first time | Normal — Maven downloads deps; subsequent builds use cache |
