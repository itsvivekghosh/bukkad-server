# Testing Guide

## Quick commands

```bash
# All unit tests
mvn test

# Full regression suite
mvn test -Dtest=com.bhukkad.regression.RegressionTestSuite

# Build with JaCoCo coverage (80% line / 70% branch minimum)
mvn clean verify

# API smoke tests (server must be running)
python3 scripts/test-all-apis.py --base-url http://localhost:8080
```

Coverage report: `target/site/jacoco/index.html`

## Test layout

```
src/test/java/com/bhukkad/
├── controller/     # MockMvc / direct controller tests
├── service/        # Service layer unit tests
├── regression/     # RegressionTestSuite (master suite)
└── logging/        # Logging infrastructure tests
```

## Priority tags

| Tag | Meaning |
|-----|---------|
| `P0` / `regression` | Blocks release — auth, orders, payments, security |
| `P1` | High — cart, delivery, restaurant, health |
| `P2` | Medium — coupons, reviews, admin |
| `P3` | Low — utilities, metrics |

Run by tag: `mvn test -Dgroups=regression`

## Coverage summary

| Area | Status |
|------|--------|
| Auth, Customer, Restaurant, Menu | Covered |
| Cart, Order, Delivery, Payment | Covered |
| Coupons, Reviews, Admin, Security | Covered |
| Wallet, Membership, Referral, Fraud | Covered |
| Promotions, Settlement, Live tracking | Covered |

Unit tests use Mockito — no database or Redis required.

## CI integration

| Workflow | When | What runs |
|----------|------|-----------|
| Feature CI | Push `feature/*` | Build + Test |
| Pull Request CI | Any PR | Build + Test + ArchUnit/Pact, Pitest, OWASP, CodeQL |
| Staging Deploy | Push / merge `deploy` | Build + Test + Docker push + Deploy to Staging |
| Production Deploy | Push / merge `main` | Build + Test + Docker push + Deploy to Production |
| Nightly Regression | Scheduled | API regression script |

## Adding tests

1. Place tests under the matching package in `src/test/java/com/bhukkad/`
2. Use `@ExtendWith(MockitoExtension.class)` for unit tests
3. Tag with `@Tag("regression")` for suite inclusion
4. Follow existing controller test patterns (`@Mock`, `@InjectMocks`, assert `ResponseEntity`)
