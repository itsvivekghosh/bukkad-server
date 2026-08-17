# CI/CD Pipeline Documentation

## Branch Strategy

| Branch | Purpose | Environment |
|--------|---------|-------------|
| `main` | Production | Production |
| `deploy` | Staging / Release Candidate | Staging |
| `feature/*` | Feature development | None |

## Release Flow

```text
feature branch
      ↓
Pull Request → deploy
      ↓
CI Checks (tests, security, quality)
      ↓
Merge to deploy
      ↓
Build + Unit Tests
      ↓
Integration Tests
      ↓
Regression Tests
      ↓
Security / Quality Checks
      ↓
Docker Build + Push
      ↓
Deploy to STAGING
      ↓
Smoke / E2E Tests
      ↓
Manual approval / release decision
      ↓
Pull Request deploy → main
      ↓
Production validation
      ↓
Deploy to PRODUCTION
```

## GitHub Actions Workflows

### 1. Pull Request CI (`pull-request.yml`)

**Trigger:** Pull request to `deploy` branch

**Jobs:**
- Compile & Test (unit tests, integration tests, regression tests)
- Dependency Review (fails on moderate+ vulnerabilities)
- CodeQL Analysis (security scanning)

**Purpose:** Validates that code is safe to merge into deploy branch.

### 2. Build Artifact (`build.yml`)

**Trigger:** Push to `deploy` branch

**Jobs:**
- Build JAR with `./mvnw clean verify`
- Upload JAR artifact (30 days retention)
- Upload JaCoCo coverage report
- Upload test reports

**Purpose:** Builds and tests the release candidate artifact.

### 3. Docker Image (`docker.yml`)

**Trigger:** After `build.yml` succeeds on `deploy` branch

**Jobs:**
- Build and push Docker image with immutable SHA tag
- Run Trivy vulnerability scan
- Upload scan results
- Store image SHA in GitHub repository variable `STAGING_IMAGE_SHA`

**Purpose:** Creates container image for staging deployment.

### 4. Deploy to Staging (`deploy-staging.yml`)

**Trigger:** After `docker.yml` succeeds on `deploy` branch

**Jobs:**
- Deploy to staging Kubernetes cluster
- Wait for rollout
- Run smoke tests

**Purpose:** Deploys tested image to staging environment.

### 5. Deploy to Production (`deploy-production.yml`)

**Trigger:** 
- Pull request from `deploy` to `main`
- Push to `main` branch

**Jobs:**
- Validate release candidate (reads staging image SHA)
- Comment PR with release info
- Deploy to production (requires manual approval)
- Run production smoke tests

**Purpose:** Deploys exact same image that passed staging to production.

### 6. Nightly Regression (`nightly-regression.yml`)

**Trigger:** Every night at 2 AM IST

**Jobs:**
- Full regression test suite
- API regression tests

**Purpose:** Detects regressions outside of normal deployment cycle.

### 7. API Regression Tests (`api-regression.yml`)

**Trigger:** Manual / Nightly at 3 AM IST

**Jobs:**
- Start MySQL and Redis
- Build and run application
- Run Python API test suite
- Upload reports

**Purpose:** End-to-end API validation.

## Branch Protection Rules

### deploy branch
- Require pull request before merging
- Require status checks to pass:
  - Compile & Test
  - Dependency Review
  - CodeQL Analysis
- Require branches to be up to date
- Do not allow bypassing settings

### main branch
- Require pull request before merging
- Require status checks to pass:
  - Validate Release Candidate
- Require approvals (at least 1)
- Require branches to be up to date
- Do not allow bypassing settings
- Require linear history

## Environment Protection

### staging
- No manual approval required
- URL: https://staging.bhukkad.com

### production
- Required reviewers: at least 1
- URL: https://bhukkad.com
- Wait timer: 0 minutes (approval only)

## Secrets

| Secret | Purpose | Used In |
|--------|---------|---------|
| `STAGING_SSH_HOST` / `STAGING_SSH_USER` / `STAGING_SSH_KEY` | SSH access to staging EC2 | deploy-staging.yml |
| `STAGING_DB_*` / `STAGING_REDIS_*` / `STAGING_JWT_SECRET` | Staging app config | deploy-staging.yml |
| `PROD_SSH_HOST` / `PROD_SSH_USER` / `PROD_SSH_KEY` | SSH access to production EC2 | deploy-production.yml |
| `PROD_DB_*` / `PROD_REDIS_*` / `PROD_JWT_SECRET` | Production app config | deploy-production.yml |
| **`GHCR_READ_TOKEN`** | **GitHub PAT with `read:packages` (required for docker pull on EC2)** | deploy-staging.yml |
| `GHCR_USERNAME` | GitHub username for GHCR (optional; default `itsvivekghosh`) | deploy-staging.yml |
| `GITHUB_TOKEN` | Auto-generated token for API access | docker.yml, deploy workflows |

### GHCR PAT setup (one-time)

1. Open [GitHub → Settings → Developer settings → Personal access tokens](https://github.com/settings/tokens)
2. **Generate new token (classic)** → enable **`read:packages`**
3. Repo → **Settings → Secrets and variables → Actions → New repository secret**
   - Name: `GHCR_READ_TOKEN`
   - Value: your PAT
4. Re-run **Deploy to Staging**

The workflow steps **Verify GHCR PAT secret** and **Login to GHCR on staging VM** run before `docker pull`.

## Docker Image Tags

| Tag | Purpose |
|-----|---------|
| `ghcr.io/itsvivekghosh/bukkad-server:<git-sha>` | Immutable release tag |
| `ghcr.io/itsvivekghosh/bukkad-server:deploy` | Latest staging deployment |

## Rollback Strategy

To rollback to a previous version:

```bash
kubectl config use-context staging
kubectl set image deployment/bhukkad-app \
  bhukkad-app=ghcr.io/itsvivekghosh/bukkad-server:<previous-sha> \
  -n bhukkad

kubectl config use-context production
kubectl set image deployment/bhukkad-app \
  bhukkad-app=ghcr.io/itsvivekghosh/bukkad-server:<previous-sha> \
  -n bhukkad
```

## Troubleshooting

### Pipeline fails at build stage
- Check Java version (must be 17)
- Check Maven wrapper is executable
- Review test failure logs in artifacts

### Docker build fails
- Check Dockerfile syntax
- Check Maven build succeeds locally
- Review Trivy scan results for vulnerabilities

### Staging deployment fails
- Check kubeconfig secret
- Check Kubernetes cluster availability
- Review pod events: `kubectl describe pod -n bhukkad`

### Production deployment fails
- Check production environment approval
- Check kubeconfig secret
- Verify image exists in registry: `ghcr.io/itsvivekghosh/bukkad-server:<sha>`

### Tests fail locally but pass in CI
- Check database/Redis availability
- Check environment variables
- Check test profiles

## Local Testing

```bash
# Run unit tests
./mvnw test -Dtest="*Test,!*IntegrationTest"

# Run integration tests
./mvnw test -Dtest="*IntegrationTest"

# Run full regression suite
./mvnw test -Dtest="com.bhukkad.regression.RegressionTestSuite"

# Run with coverage
./mvnw clean verify

# Run API tests (requires running server)
python3 scripts/test-all-apis.py
```
