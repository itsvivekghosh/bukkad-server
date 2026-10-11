# Load Test Configuration for Bhukkad Platform
# Target: 200,000 TPS sustained

## Test Scenarios

### 1. Baseline Load Test (Warm-up)
- Duration: 10 minutes
- Ramp-up: 2 minutes
- Target TPS: 10,000
- Purpose: Verify system stability at low load, warm up caches/JIT

### 2. Steady State Load Test
- Duration: 60 minutes
- Ramp-up: 5 minutes
- Target TPS: 50,000
- Purpose: Verify sustained throughput, check for memory leaks, GC behavior

### 3. Peak Load Test
- Duration: 30 minutes
- Ramp-up: 3 minutes
- Target TPS: 100,000
- Purpose: Test system under high load, identify bottlenecks

### 4. Stress Test (Breaking Point)
- Duration: 20 minutes
- Ramp-up: 2 minutes
- Target TPS: 200,000+ (until error rate > 1%)
- Purpose: Find system breaking point, observe degradation patterns

### 5. Spike Test
- Duration: 15 minutes
- Pattern: 10k TPS baseline → 100k TPS spike (2 min) → 10k TPS (repeated 3x)
- Purpose: Test auto-scaling, circuit breaker behavior, recovery time

### 6. Soak Test (Endurance)
- Duration: 4 hours
- Target TPS: 30,000
- Purpose: Detect memory leaks, connection leaks, log disk usage

### 7. Failover Test
- Duration: 30 minutes
- Target TPS: 20,000
- Chaos: Kill 1 Redis node, 1 PostgreSQL replica, 1 service pod
- Purpose: Verify HA, failover time, data consistency

### 8. Circuit Breaker Test
- Duration: 15 minutes
- Target TPS: 10,000
- Chaos: Inject 50% latency on commerce service
- Purpose: Verify circuit breaker opens, fallback works, recovery

## API Endpoints to Test

### High-Volume Endpoints (80% of traffic)
1. `GET /api/v1/restaurants/public/search` - Restaurant search
2. `GET /api/v1/menu/restaurant/{id}` - Menu retrieval
3. `POST /api/v1/cart/add` - Add to cart
4. `GET /api/v1/cart/items` - View cart
5. `POST /api/v1/orders` - Create order
6. `GET /api/v1/orders/customer/{id}` - Order history

### Medium-Volume Endpoints (15% of traffic)
1. `POST /api/v1/payments/initiate` - Payment initiation
2. `POST /api/v1/payments/webhooks/razorpay` - Payment webhook
3. `GET /api/v1/deliveries/track/{id}` - Delivery tracking

### Low-Volume Endpoints (5% of traffic)
1. `POST /api/v1/auth/login` - Login
2. `POST /api/v1/customers/register` - Registration
3. `POST /api/v1/admin/*` - Admin operations

## Test Data Requirements

### Restaurants: 10,000
- Each with 50-200 menu items
- Geographic distribution across 50 cities

### Customers: 1,000,000
- 80% active, 20% dormant
- Mix of tiers: Free (60%), Premium (25%), Gold (10%), Platinum (5%)

### Orders: 500,000/day
- Mix of: dine-in (30%), takeaway (50%), delivery (20%)
- Payment methods: UPI (50%), Card (30%), Wallet (15%), COD (5%)

## Monitoring During Tests

### Key Metrics to Track
| Metric | Target | Alert Threshold |
|--------|--------|-----------------|
| API p99 latency | < 200ms | > 500ms |
| Error rate | < 0.1% | > 1% |
| CPU utilization | < 70% | > 85% |
| Memory utilization | < 75% | > 90% |
| GC pause time | < 50ms | > 200ms |
| DB connection pool usage | < 60% | > 80% |
| Redis memory | < 70% | > 85% |
| Kafka consumer lag | < 1000 | > 10000 |

### Distributed Tracing
- Sample rate: 10% (prod), 100% (test)
- Track: end-to-end latency, service hop count, error propagation

## Load Test Tools

### Primary: k6 (JavaScript-based, good for HTTP)
```bash
k6 run --vus 1000 --duration 10m load-test.js
```

### Secondary: Gatling (Scala-based, good for complex scenarios)
```bash
gatling.sh -s bhukkad.LoadTestSimulation
```

### Tertiary: Locust (Python-based, good for complex user flows)
```bash
locust -f locustfile.py --users 5000 --spawn-rate 100
```

## CI/CD Integration

### GitHub Actions Workflow
```yaml
name: Load Test
on:
  schedule:
    - cron: '0 2 * * 0'  # Weekly on Sunday 2 AM
  workflow_dispatch:
    inputs:
      scenario:
        type: choice
        options: [baseline, steady, peak, stress, spike, soak]
        default: steady

jobs:
  load-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run k6 load test
        uses: grafana/k6-action@v0.2.0
        with:
          filename: load-tests/${{ github.event.inputs.scenario }}.js
          env: |
            BASE_URL=https://staging.bhukkad.com
            REDIS_PASSWORD=${{ secrets.REDIS_PASSWORD }}
```

## Results Analysis

### Pass Criteria
- All scenarios: Error rate < 0.1%
- Steady state: p99 < 200ms at 50k TPS
- Peak: p99 < 500ms at 100k TPS
- Stress: System degrades gracefully (no cascade failures)
- Soak: No memory/connection leaks over 4 hours

### Report Template
```markdown
# Load Test Report - {{scenario}} - {{date}}

## Summary
- Target TPS: {{target}}
- Achieved TPS: {{achieved}}
- Error Rate: {{error_rate}}%
- p50 Latency: {{p50}}ms
- p95 Latency: {{p95}}ms
- p99 Latency: {{p99}}ms

## Bottlenecks Identified
1. {{bottleneck_1}}
2. {{bottleneck_2}}

## Recommendations
1. {{rec_1}}
2. {{rec_2}}

## Graphs
- Latency percentile chart
- Throughput over time
- Error rate over time
- Resource utilization (CPU, Memory, DB, Redis, Kafka)
```