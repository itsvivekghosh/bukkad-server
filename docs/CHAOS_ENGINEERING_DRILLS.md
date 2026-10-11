# Chaos Engineering Drills - Bhukkad Platform

## Overview
This document defines the chaos engineering program for validating resilience of the Bhukkad platform under failure conditions. Drills are run on a regular cadence to verify failure handling, graceful degradation, and recovery procedures.

---

## Drill Schedule

| Frequency | Drill Type | Duration | Environment |
|-----------|------------|----------|-------------|
| **Weekly** | Pod Kill / Network Partition | 30 min | Staging |
| **Bi-weekly** | Dependency Failure Injection | 45 min | Staging |
| **Monthly** | Full Region Failover | 60 min | Staging → Prod shadow |
| **Quarterly** | Data Center Outage Simulation | 2 hours | Prod (controlled) |
| **Annually** | Complete Disaster Recovery | 4 hours | DR environment |

---

## Drill Definitions

### 1. Pod Kill Drill (Weekly)
**Objective**: Verify Kubernetes self-healing, graceful shutdown, and traffic redistribution.

**Procedure**:
```bash
# 1. Identify target service pods
kubectl get pods -n bhukkad -l app=bhukkad-commerce

# 2. Kill one pod (simulate OOM/crash)
kubectl delete pod bhukkad-commerce-xxxxx -n bhukkad --grace-period=0

# 3. Verify:
#    - New pod spins up within 60s
#    - Traffic redistributed (no 5xx spike > 1%)
#    - Kafka consumer group rebalances within 30s
#    - WebSocket connections gracefully drain
#    - Circuit breakers don't trip erroneously
```

**Success Criteria**:
- [ ] New pod Ready within 60s
- [ ] Error rate < 1% during transition
- [ ] p99 latency < 2x baseline
- [ ] No data loss (orders, payments, events)

---

### 2. Network Partition Drill (Weekly)
**Objective**: Verify service mesh resilience, timeout handling, circuit breaker behavior.

**Procedure**:
```bash
# 1. Install chaos mesh or use iptables
# 2. Partition commerce from catalog (simulate AZ failure)
kubectl apply -f - <<EOF
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata:
  name: commerce-catalog-partition
  namespace: bhukkad
spec:
  action: partition
  direction: both
  target:
    selector:
      namespaces:
        - bhukkad
      labelSelectors:
        app: bhukkad-commerce
  mode: all
  scheduler:
    cron: "@once"
EOF

# 3. Verify:
#    - Circuit breakers OPEN for restaurant routes
#    - Fallback responses returned (503 with retry-after)
#    - No cascade failures to other services
#    - Orders still process (catalog not needed for cart)

# 4. Restore
kubectl delete networkchaos commerce-catalog-partition -n bhukkad
```

**Success Criteria**:
- [ ] Circuit breaker opens within 10s
- [ ] Fallback 503 rate < 5% of total traffic
- [ ] Order creation still works (99%+)
- [ ] Recovery within 30s of restore

---

### 3. Dependency Failure Injection (Bi-weekly)

#### 3a. Redis Failure
```bash
# Simulate Redis outage
kubectl scale statefulset bhukkad-redis --replicas=0 -n bhukkad

# Verify:
# - Rate limiting fails open (configurable)
# - Idempotency keys stored in DB fallback
# - Session cache misses (re-login required)
# - Outbox wake disabled, falls back to polling
# - Feature flags use local cache

# Restore
kubectl scale statefulset bhukkad-redis --replicas=3 -n bhukkad
```

**Success Criteria**:
- [ ] API stays up (fail-open for rate limit)
- [ ] Idempotency still works (DB-backed)
- [ ] Feature flag changes propagate within 5 min
- [ ] Recovery auto-heals within 2 min

#### 3b. PostgreSQL Failure (Primary)
```bash
# Kill primary PostgreSQL pod
kubectl delete pod bhukkad-postgres-0 -n bhukkad --grace-period=0

# Verify:
# - Patroni fails over to replica within 30s
# - PgBouncer reconnects to new primary
# - Services retry with exponential backoff
# - No data loss (synchronous replication)

# Verify promotion
kubectl exec -it bhukkad-postgres-1 -n bhukkad -- patronictl list
```

**Success Criteria**:
- [ ] Failover completes within 30s
- [ ] Zero data loss (sync replication)
- [ ] Connection pool recovers without restart
- [ ] Write latency spike < 5s

#### 3c. Kafka Broker Failure
```bash
# Kill one Kafka broker
kubectl delete pod kafka-0 -n kafka --grace-period=0

# Verify:
# - ISR shrinks but stays quorum
# - Producer retries with backoff
# - Consumer group rebalances
# - No message loss (acks=all)
```

**Success Criteria**:
- [ ] Broker restarts and catches up
- [ ] Consumer lag < 10k after recovery
- [ ] No duplicate message processing
- [ ] DLQ not growing abnormally

---

### 4. Latency Injection Drill (Bi-weekly)
**Objective**: Verify timeout handling, circuit breaker thresholds, graceful degradation.

```bash
# Inject 500ms latency on commerce -> payment path
kubectl apply -f - <<EOF
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata:
  name: commerce-payment-latency
  namespace: bhukkad
spec:
  action: delay
  delay:
    latency: "500ms"
    correlation: "100"
    jitter: "100ms"
  target:
    selector:
      namespaces:
        - bhukkad
      labelSelectors:
        app: bhukkad-commerce
  direction: to
  destination:
    selector:
      namespaces:
        - bhukkad
      labelSelectors:
        app: bhukkad-commerce
  mode: all
  scheduler:
    cron: "@once"
EOF
```

**Verify**:
- [ ] Circuit breaker trips at configured threshold (50% errors)
- [ ] Fallback responses returned for payment endpoints
- [ ] Cart/Order operations not affected
- [ ] Metrics show latency spike, circuit breaker state change
- [ ] Recovery within 10s of removing chaos

---

### 5. Resource Exhaustion Drill (Monthly)

#### 5a. CPU Exhaustion
```bash
# Stress test one commerce pod
kubectl exec -it bhukkad-commerce-xxxxx -n bhukkad -- stress-ng --cpu 4 --timeout 60s

# Verify:
# - HPA scales out within 2 min
# - Load redistributes to healthy pods
# - No OOM kills
# - Circuit breakers protect downstream
```

#### 5b. Memory Pressure
```bash
# Fill memory on one pod
kubectl exec -it bhukkad-commerce-xxxxx -n bhukkad -- \
  stress-ng --vm 2 --vm-bytes 80% --timeout 60s

# Verify:
# - JVM GC handles pressure (G1 pauses < 200ms)
# - No OOM kill (ExitOnOutOfMemoryError)
# - Pod restarts if OOM, traffic shifts
```

#### 5c. Disk Pressure
```bash
# Fill disk on PostgreSQL
kubectl exec -it bhukkad-postgres-0 -n bhukkad -- \
  dd if=/dev/zero of=/tmp/fill bs=1M count=10000

# Verify:
# - Alert fires at 80% disk
# - Log rotation works
# - WAL archiving continues
```

---

### 6. Full Region Failover (Monthly)
**Objective**: Verify multi-AZ / multi-region failover capability.

**Procedure**:
```bash
# 1. Simulate AZ failure (network partition entire AZ)
# 2. Verify:
#    - Services in healthy AZ handle full load
#    - Database promotes replica in healthy AZ
#    - Kafka ISR maintains quorum
#    - Redis sentinel promotes replica
#    - DNS/Load balancer redirects traffic

# 3. Measure:
#    - RTO (Recovery Time Objective): < 5 min
#    - RPO (Recovery Point Objective): 0 (sync replication)
#    - Error rate during failover: < 2%

# 4. Failback procedure
```

**Success Criteria**:
- [ ] RTO < 5 minutes
- [ ] RPO = 0 (zero data loss)
- [ ] Error rate < 2% during failover
- [ ] All services healthy in target AZ
- [ ] Manual approval required for failback

---

### 7. Data Corruption / Loss Drill (Quarterly)

#### 7a. Accidental Table Drop
```bash
# Simulate accidental DROP TABLE (on staging only!)
# Verify:
# - Point-in-time recovery (PITR) works
# - RPO < 5 minutes
# - Flyway migration history intact
# - Service recovers after DB restore
```

#### 7b. Flyway Migration Failure
```bash
# Deploy broken migration (checksum mismatch)
# Verify:
# - Flyway repair works
# - Service doesn't start with broken schema
# - Alert fires on migration failure
# - Rollback procedure documented
```

---

## Automation & CI/CD Integration

### GitHub Actions Workflow
```yaml
# .github/workflows/chaos-drill.yml
name: Chaos Engineering Drill

on:
  schedule:
    - cron: '0 2 * * 1'  # Weekly Monday 2 AM
  workflow_dispatch:
    inputs:
      drill_type:
        type: choice
        options: [pod_kill, network_partition, redis_failure, postgres_failure, latency_injection, cpu_stress, full_failover]
        required: true
      environment:
        type: choice
        options: [staging, production]
        default: staging

jobs:
  chaos-drill:
    runs-on: ubuntu-latest
    environment: ${{ github.event.inputs.environment }}
    steps:
      - uses: actions/checkout@v4
      
      - name: Install Chaos Mesh CLI
        run: |
          curl -sSL https://mirrors.chaos-mesh.org/v2.6.0/install.sh | bash
          
      - name: Run Drill
        id: drill
        run: |
          case "${{ github.event.inputs.drill_type }}" in
            pod_kill)
              ./scripts/chaos/pod-kill.sh ${{ github.event.inputs.environment }}
              ;;
            network_partition)
              ./scripts/chaos/network-partition.sh ${{ github.event.inputs.environment }}
              ;;
            redis_failure)
              ./scripts/chaos/redis-failure.sh ${{ github.event.inputs.environment }}
              ;;
            postgres_failure)
              ./scripts/chaos/postgres-failure.sh ${{ github.event.inputs.environment }}
              ;;
            latency_injection)
              ./scripts/chaos/latency-injection.sh ${{ github.event.inputs.environment }}
              ;;
            cpu_stress)
              ./scripts/chaos/cpu-stress.sh ${{ github.event.inputs.environment }}
              ;;
            full_failover)
              ./scripts/chaos/full-failover.sh ${{ github.event.inputs.environment }}
              ;;
          esac
          
      - name: Collect Metrics
        run: |
          # Query Prometheus for drill metrics
          ./scripts/chaos/collect-metrics.sh ${{ github.event.inputs.drill_type }}
          
      - name: Verify Success Criteria
        run: |
          ./scripts/chaos/verify-criteria.sh ${{ github.event.inputs.drill_type }}
          
      - name: Generate Report
        if: always()
        run: |
          ./scripts/chaos/generate-report.sh > chaos-report.md
          
      - name: Upload Report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: chaos-drill-report
          path: chaos-report.md
          
      - name: Comment on Issue
        if: github.event_name == 'workflow_dispatch'
        uses: actions/github-script@v7
        with:
          script: |
            const fs = require('fs');
            const report = fs.readFileSync('chaos-report.md', 'utf8');
            github.rest.issues.createComment({
              issue_number: ${{ github.event.inputs.issue_number || 0 }},
              owner: context.repo.owner,
              repo: context.repo.repo,
              body: report
            });
```

---

## Drill Scripts

### Pod Kill Script
```bash
#!/bin/bash
# scripts/chaos/pod-kill.sh

ENV=$1
SERVICE=${2:-commerce}
NAMESPACE="bhukkad-${ENV}"

echo "=== Pod Kill Drill: ${SERVICE} in ${NAMESPACE} ==="

# Get pods
PODS=$(kubectl get pods -n ${NAMESPACE} -l app=bhukkad-${SERVICE} -o jsonpath='{.items[*].metadata.name}')
POD_ARRAY=($PODS)

if [ ${#POD_ARRAY[@]} -eq 0 ]; then
  echo "ERROR: No pods found for ${SERVICE}"
  exit 1
fi

# Pick random pod
TARGET_POD=${POD_ARRAY[$RANDOM % ${#POD_ARRAY[@]}]}
echo "Target pod: ${TARGET_POD}"

# Record pre-drill metrics
PRE_ERROR_RATE=$(curl -s "http://prometheus:9090/api/v1/query?query=rate(http_requests_total{status=~'5..'}[1m])" | jq -r '.data.result[0].value[1]')

# Kill pod
echo "Killing pod ${TARGET_POD}..."
kubectl delete pod ${TARGET_POD} -n ${NAMESPACE} --grace-period=0 --force

# Wait for replacement
echo "Waiting for replacement pod..."
kubectl wait --for=condition=Ready pod -l app=bhukkad-${SERVICE} -n ${NAMESPACE} --timeout=120s

# Record post-drill metrics
sleep 30
POST_ERROR_RATE=$(curl -s "http://prometheus:9090/api/v1/query?query=rate(http_requests_total{status=~'5..'}[1m])" | jq -r '.data.result[0].value[1]')

echo "Pre-drill error rate: ${PRE_ERROR_RATE}"
echo "Post-drill error rate: ${POST_ERROR_RATE}"

# Verify criteria
if (( $(echo "${POST_ERROR_RATE} > 0.01" | bc -l) )); then
  echo "FAIL: Error rate exceeded 1%"
  exit 1
fi

echo "SUCCESS: Pod kill drill passed"
```

### Network Partition Script
```bash
#!/bin/bash
# scripts/chaos/network-partition.sh

ENV=$1
NAMESPACE="bhukkad-${ENV}"

echo "=== Network Partition Drill: Commerce <-> Catalog ==="

# Create partition
cat <<EOF | kubectl apply -f -
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata:
  name: commerce-catalog-partition
  namespace: ${NAMESPACE}
spec:
  action: partition
  direction: both
  target:
    selector:
      namespaces:
        - ${NAMESPACE}
      labelSelectors:
        app: bhukkad-commerce
  mode: all
  scheduler:
    cron: "@once"
EOF

echo "Partition created. Waiting 60s for circuit breaker..."
sleep 60

# Check circuit breaker state
CB_STATE=$(curl -s "http://prometheus:9090/api/v1/query?query=resilience4j_circuitbreaker_state{name='restaurant'}" | jq -r '.data.result[0].value[1]')
echo "Circuit breaker state: ${CB_STATE}"

# Verify fallback rate
FALLBACK_RATE=$(curl -s "http://prometheus:9090/api/v1/query?query=rate(http_requests_total{status='503',service='gateway'}[1m])" | jq -r '.data.result[0].value[1]')
echo "Fallback (503) rate: ${FALLBACK_RATE}"

# Restore
echo "Restoring connectivity..."
kubectl delete networkchaos commerce-catalog-partition -n ${NAMESPACE}

# Wait for recovery
sleep 30

# Verify recovery
CB_STATE=$(curl -s "http://prometheus:9090/api/v1/query?query=resilience4j_circuitbreaker_state{name='restaurant'}" | jq -r '.data.result[0].value[1]')
echo "Circuit breaker state after restore: ${CB_STATE}"

if [ "${CB_STATE}" != "0" ]; then
  echo "FAIL: Circuit breaker did not close"
  exit 1
fi

echo "SUCCESS: Network partition drill passed"
```

---

## Metrics Collection

### Key Metrics to Capture
```promql
# Error rate
rate(http_requests_total{status=~"5.."}[1m])

# Latency percentiles
histogram_quantile(0.99, rate(http_request_duration_seconds_bucket[1m]))

# Circuit breaker state
resilience4j_circuitbreaker_state

# Kafka consumer lag
kafka_consumer_group_lag

# Database connections
hikaricpu_active_connections / hikaricpu_max_connections

# Redis memory
redis_memory_used_bytes / redis_memory_max_bytes

# Pod restarts
kube_pod_container_status_restarts_total

# HPA replica count
kube_horizontalpodautoscaler_status_desired_replicas
```

---

## Reporting Template

```markdown
# Chaos Drill Report

## Drill Details
- **Date**: {{date}}
- **Type**: {{drill_type}}
- **Environment**: {{environment}}
- **Duration**: {{duration}}
- **Triggered by**: {{user}}

## Target Services
- {{service_list}}

## Pre-Drill Baseline
- Error rate: {{pre_error_rate}}%
- p99 latency: {{pre_p99}}ms
- Active pods: {{pre_pods}}

## During Drill
- Peak error rate: {{peak_error_rate}}%
- Peak p99 latency: {{peak_p99}}ms
- Circuit breakers triggered: {{cb_triggered}}
- Fallback responses: {{fallback_count}}

## Recovery
- Recovery time: {{recovery_time}}s
- Post-recovery error rate: {{post_error_rate}}%
- Post-recovery p99 latency: {{post_p99}}ms

## Success Criteria
| Criteria | Target | Actual | Pass/Fail |
|----------|--------|--------|-----------|
| Error rate < 1% | 1% | {{peak_error_rate}}% | {{pass_fail}} |
| Recovery < 60s | 60s | {{recovery_time}}s | {{pass_fail}} |
| No data loss | 0 | {{data_loss}} | {{pass_fail}} |

## Observations
{{observations}}

## Action Items
1. {{action_1}}
2. {{action_2}}

## Next Drill
- **Scheduled**: {{next_date}}
- **Type**: {{next_type}}
```

---

## Runbook Links

| Drill | Runbook |
|-------|---------|
| Pod Kill | `RUNBOOKS/pod-kill.md` |
| Network Partition | `RUNBOOKS/network-partition.md` |
| Redis Failure | `RUNBOOKS/redis-failure.md` |
| PostgreSQL Failure | `RUNBOOKS/postgres-failure.md` |
| Kafka Failure | `RUNBOOKS/kafka-failure.md` |
| Latency Injection | `RUNBOOKS/latency-injection.md` |
| CPU/Memory Stress | `RUNBOOKS/resource-exhaustion.md` |
| Full Failover | `RUNBOOKS/full-failover.md` |

---

## Safety Rules

1. **NEVER** run destructive drills on production without explicit approval
2. **ALWAYS** have rollback plan ready before starting
3. **ALWAYS** notify on-call before starting any drill
4. **ALWAYS** have monitoring dashboard open during drill
5. **STOP IMMEDIATELY** if error rate > 5% or latency > 10x baseline
6. **DOCUMENT** all findings and action items
7. **REVIEW** drill results in weekly ops meeting