# Local Development (without Docker)

Run the Spring Boot app on your host machine with local or containerized MySQL/Redis.

## 1. Start MySQL

Create database and user:

```sql
CREATE DATABASE IF NOT EXISTS bhukkad CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'bhukkad_user'@'%' IDENTIFIED BY 'bhukkad_pass';
GRANT ALL PRIVILEGES ON bhukkad.* TO 'bhukkad_user'@'%';
FLUSH PRIVILEGES;
```

Or use root with password matching `application-dev.yml` defaults.

## 2. Start Redis

```bash
redis-server
# or: docker run -d -p 6379:6379 redis:7-alpine
```

## 3. Environment variables

The **dev** profile is active by default (`spring.profiles.active=dev` in `application.yml`).

Minimum overrides (if not using defaults):

```bash
export DB_HOST=localhost
export DB_PORT=3306
export DB_NAME=bhukkad
export DB_USERNAME=bhukkad_user
export DB_PASSWORD=bhukkad_pass
export REDIS_HOST=localhost
export REDIS_PORT=6379
export JWT_SECRET="404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"
```

See [configuration.md](./configuration.md) for the full list.

## 4. Build and run

```bash
# From repository root
mvn clean package -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Or run from your IDE:

- Main class: `com.bhukkad.BackendServerApplication`
- Active profile: `dev`
- JDK: 17

## 5. Flyway migrations

On startup, Flyway applies migrations from `src/main/resources/db/migration/`:

| Version | Purpose |
|---------|---------|
| V1 | Baseline schema (users, orders, cart, etc.) |
| V2 | Reliability (outbox, idempotency) |
| V3 | Search & geo indexes |
| V4 | Wallet, push tokens, rider earnings, split pay |

`spring.jpa.hibernate.ddl-auto=validate` — schema is **never** auto-generated; always use Flyway.

### Reset database (dev only)

```bash
mysql -u root -p -e "DROP DATABASE bhukkad; CREATE DATABASE bhukkad;"
# Restart app — Flyway re-runs migrations
```

## 6. Verify

```bash
curl http://localhost:8080/api/v1/health/ping
curl http://localhost:8080/api/v1/health/detailed | jq .
```

Swagger: `http://localhost:8080/swagger-ui.html`

## 7. Hot reload

`spring-boot-devtools` is enabled in dev. IDE auto-compile + restart speeds up iteration.

## 8. Debug port (optional)

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
```

Attach your IDE debugger to port `5005`.

## 9. Common local issues

| Symptom | Fix |
|---------|-----|
| `Communications link failure` | MySQL not running or wrong `DB_HOST`/`DB_PORT` |
| `RedisConnectionFailure` | Start Redis or set `REDIS_HOST` |
| Flyway validation error | DB schema out of sync — reset DB or run pending migrations |
| `JWT secret` errors in prod profile | Set `JWT_SECRET` (required in prod) |
| Port 8080 in use | `export SERVER_PORT=8081` |

## 10. Running tests locally

```bash
mvn clean test

# Single test class
mvn test -Dtest=OrderServiceImplTest

# With coverage (if configured)
mvn verify
```

Tests use mocks; they do **not** require MySQL/Redis unless you add integration tests.
