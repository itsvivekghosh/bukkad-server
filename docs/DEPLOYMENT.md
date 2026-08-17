# Bhukkad Deployment Guide

## Branch → pipeline map

| Branch | What runs automatically | Environment | Manual step |
|--------|-------------------------|-------------|-------------|
| `feature/*` | Nothing deploys | — | Open PR to `deploy` |
| `deploy` | **Pull Request CI** (on PR) | — | Merge when CI passes |
| `deploy` | **Docker Image** (test + build + push) | — | Auto on push |
| `deploy` | **Deploy to Staging** | Staging EC2 | Auto after Docker Image succeeds |
| `main` | **Docker Image** (test + build + push) | — | Auto on push |
| `main` | **Deploy to Production** | Production EC2 | **Manual** — Actions → Deploy to Production |
| `main` | PR to `main` | — | **Validate Release Candidate** (comment only) |

```text
feature/* ──PR──► deploy ──push──► Docker Image ──► Deploy to Staging
                                    │
deploy ──PR──► main ──push──► Docker Image
                                    │
                         (manual) Deploy to Production
```

### Nightly (scheduled)

- **Nightly Regression** — API tests on GitHub runners (not EC2).

---

## Configuration checklist

### GitHub Variables (non-secret)

**Settings → Secrets and variables → Actions → Variables**

| Variable | Purpose | Example |
|----------|---------|---------|
| `STAGING_EC2_HOST` | Staging EC2 public DNS | `ec2-65-1-112-216...` |
| `PROD_EC2_HOST` | Production EC2 public DNS | `ec2-13-201-21-45...` |
| `GHCR_IMAGE` | Docker image repository | `ghcr.io/itsvivekghosh/bukkad-server` |
| `GHCR_USERNAME` | GHCR login user | `itsvivekghosh` |
| `APP_PORT` | App HTTP port | `8080` |
| `STAGING_IMAGE_SHA` | Set by Docker workflow | (auto-updated) |
| `STAGING_REDIS_LOCAL` | Use Redis on staging EC2 | `true` |
| `PROD_REDIS_LOCAL` | Use Redis on production EC2 | `true` |
| `FLYWAY_REPAIR_ON_DEPLOY` | Run Flyway repair before staging deploy | `false` |

Local mirror: `.github/deploy-config.env`

### GitHub Secrets (sensitive)

**Settings → Secrets and variables → Actions → Secrets**

| Secret | Staging | Production |
|--------|---------|------------|
| SSH key (`.pem` contents) | `STAGING_SSH_KEY` | `PROD_SSH_KEY` |
| SSH user | `STAGING_SSH_USER` | `PROD_SSH_USER` |
| SSH host (optional override) | `STAGING_SSH_HOST` | `PROD_SSH_HOST` |
| RDS hostname | `STAGING_DB_HOST` | `PROD_DB_HOST` |
| RDS port | `STAGING_DB_PORT` | `PROD_DB_PORT` |
| Database name | `STAGING_DB_NAME` | `PROD_DB_NAME` |
| DB username | `STAGING_DB_USERNAME` | `PROD_DB_USERNAME` |
| DB password | `STAGING_DB_PASSWORD` | `PROD_DB_PASSWORD` |
| JWT secret (≥32 chars) | `STAGING_JWT_SECRET` | `PROD_JWT_SECRET` |
| Redis password | `STAGING_REDIS_PASSWORD` | `PROD_REDIS_PASSWORD` |
| GHCR pull token | `GHCR_READ_TOKEN` | `GHCR_READ_TOKEN` |
| JWT expiry (shared) | `JWT_EXPIRATION` | `JWT_EXPIRATION` |
| JWT refresh (shared) | `JWT_REFRESH_EXPIRATION` | `JWT_REFRESH_EXPIRATION` |

**RDS endpoints (current):**

- Staging: `bhukkad-staging-db.cpeguk0sga1j.ap-south-1.rds.amazonaws.com`
- Production: `bhukkad-prod.cpeguk0sga1j.ap-south-1.rds.amazonaws.com`

### AWS (not in GitHub)

- RDS security group: allow MySQL **3306** from EC2 security group
- EC2 security group: allow SSH **22** from GitHub Actions (or `0.0.0.0/0`)
- Optional: Elastic IP on EC2 so DNS does not change on stop/start

---

## After every code change

### On `feature/*` branches

1. Push code and open PR → **`deploy`**
2. Wait for **Pull Request CI** (tests, CodeQL)
3. Merge to `deploy` when green

### After merge to `deploy` (staging)

1. **Docker Image** runs (tests + build + push to GHCR)
2. **Deploy to Staging** runs automatically
3. Verify: `curl http://<STAGING_EC2_HOST>:8080/api/v1/health/ping`
4. Optional: `python3 scripts/test-all-apis.py --base-url http://<host>:8080`

**You do nothing** unless the workflow fails — then check Actions logs.

### Releasing to production (`main`)

1. Open PR: `deploy` → `main`
2. Merge to `main` (Docker Image builds on push)
3. Go to **Actions → Deploy to Production → Run workflow** (manual)
4. Approve if environment protection is enabled
5. Verify: `curl http://<PROD_EC2_HOST>:8080/api/v1/health/ping`

### When EC2 IP/DNS changes

1. Update GitHub Variables: `STAGING_EC2_HOST` / `PROD_EC2_HOST`
2. Update `.github/deploy-config.env` (keep in sync)
3. Update SSH secrets if using a new `.pem` key
4. Re-run deploy workflow

### When RDS changes

1. Update `STAGING_DB_*` or `PROD_DB_*` secrets
2. Ensure RDS security group allows EC2 on port 3306
3. Re-run deploy

### Local validation before pushing CI changes

```bash
bash scripts/validate-deploy-workflows.sh
```

---

## Test

Health: `GET {endpoint}/api/v1/health/ping`
