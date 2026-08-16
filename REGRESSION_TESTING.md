# Bhukkad Regression Testing Guide

## Test Architecture

The regression testing suite follows the existing project test patterns and is organized as follows:

```
src/test/java/com/bhukkad/
├── controller/          # Controller unit tests (Mockito + MockMvc pattern)
├── service/             # Service layer tests
├── repository/          # Repository integration tests
├── security/            # Security configuration tests
├── cache/               # Cache behavior tests
├── order/               # Order processing tests
├── payment/             # Payment flow tests
├── delivery/            # Delivery agent tests
├── restaurant/          # Restaurant management tests
├── membership/          # Membership plan tests
├── referral/            # Referral system tests
├── review/              # Review and rating tests
├── coupon/              # Coupon validation tests
├── fraud/               # Fraud detection tests
├── inventory/           # Inventory management tests
├── notification/        # Notification dispatch tests
├── wallet/              # Wallet transaction tests
├── zone/                # Delivery zone tests
├── promotion/           # Promotion engine tests
├── settlement/          # Settlement and payout tests
├── storage/             # File storage tests
├── outbox/              # Outbox event tests
├── idempotency/         # Idempotency key tests
├── ratelimit/           # Rate limiting tests
├── metrics/             # Metrics collection tests
├── cluster/             # Cluster coordination tests
├── datasource/          # Datasource configuration tests
├── support/             # Support ticket tests
├── timeline/            # Order timeline tests
├── live/                # Live order tracking tests
├── feed/                # Home feed tests
├── logging/             # Logging and audit tests
├── util/                # Utility class tests
└── regression/
    └── RegressionTestSuite.java  # Master test suite
```

## Test Categories

### P0 - Critical
Tests that block release if they fail:
- Authentication (register, login, token refresh)
- Core order flow (create order, track order)
- Payment processing
- Security constraints (unauthorized access rejection)

### P1 - High
Important business functionality:
- Customer profile and wallet
- Restaurant management
- Menu browsing and search
- Cart operations
- Delivery agent operations
- Health and platform status

### P2 - Medium
Non-critical functionality:
- Coupons and promotions
- Reviews and ratings
- Notifications
- Admin operations

### P3 - Low
Minor functionality:
- Logging and metrics
- Utility classes

## How to Run Tests

### Run all tests
```bash
mvn test
```

### Run regression suite
```bash
mvn test -Dtest=com.bhukkad.regression.RegressionTestSuite
```

### Run specific test class
```bash
mvn test -Dtest=com.bhukkad.controller.AuthControllerTest
```

### Run tests by pattern
```bash
mvn test -Dtest="com.bhukkad.controller.*Test"
```

### Run with specific tag
```bash
mvn test -Dgroups=regression
```

## Test Data Strategy

- **Unit tests**: Use Mockito mocks for all external dependencies
- **Controller tests**: Mock service layer, test request/response mapping
- **Service tests**: Mock repositories and external clients
- **No test database**: All tests use mocked data; no external dependencies required

## Environment Requirements

- Java 17
- Maven 3.8+
- No database required for unit tests
- No Redis required for unit tests
- No external API keys required for unit tests

## Coverage

JaCoCo is configured with:
- Line coverage minimum: 80%
- Branch coverage minimum: 70%

Generate coverage report:
```bash
mvn clean verify
```

Coverage report location: `target/site/jacoco/index.html`

## Existing Test Coverage

The project already has comprehensive test coverage:
- **120 test files**
- **923 test cases**
- **0 failures** (all tests passing)

### Controller Test Coverage
- AuthControllerTest: 8 tests
- CustomerControllerTest: 12 tests
- RestaurantControllerTest: 10 tests
- MenuControllerTest: Multiple tests
- CartControllerTest: Multiple tests
- OrderControllerTest: Multiple tests
- DeliveryControllerTest: Multiple tests
- HealthControllerTest: Multiple tests
- CouponControllerTest: Multiple tests
- ReviewControllerTest: Multiple tests
- AdminControllerTest: Multiple tests

## Adding New Regression Tests

1. Create test class in appropriate package under `src/test/java/com/bhukkad/`
2. Use `@ExtendWith(MockitoExtension.class)` for Mockito tests
3. Tag with `@Tag("regression")` for suite inclusion
4. Follow existing patterns:
   - Mock service dependencies with `@Mock`
   - Inject mocks with `@InjectMocks`
   - Call controller methods directly
   - Assert on `ResponseEntity` status and body
   - Verify interactions with `verify()`

## Known Limitations

- Controller tests mock the service layer; integration tests with real database require separate setup
- Some advanced features (e.g., WebSocket, SSE) have limited test coverage
- External API integrations (payment gateways, SMS providers) are mocked

## CI/CD Integration

The regression suite is designed to run in CI/CD pipelines:
```bash
# Quick smoke test
mvn test -Dtest=com.bhukkad.controller.AuthControllerTest,com.bhukkad.controller.CustomerControllerTest

# Full regression
mvn test -Dtest=com.bhukkad.regression.RegressionTestSuite

# Complete build with coverage
mvn clean verify
```
