# Capacity Limits Documentation - Bhukkad Platform

## Overview
This document defines the capacity limits, scaling characteristics, and resource requirements for each service in the Bhukkad platform at 200k+ TPS target.

---

## Service Capacity Summary

| Service | Max TPS | Hikari Max Pool | PgBouncer Pool | Redis Connections | Kafka Consumers | JVM Heap | CPU Cores |
|---------|---------|-----------------|----------------|-------------------|-----------------|----------|-----------|
| **Gateway** | 200,000+ | N/A (reactive) | N/A | 256 | N/A | 1.5 GB | 4 |
| **Identity** | 30,000 | 20 | 500 | 64 | 3 | 1 GB | 2 |
| **Commerce** | 50,000 | 30 | 500 | 128 | 3 | 2 GB | 4 |
| **Catalog** | 35,000 | 20 | 500 | 64 | 3 | 1.5 GB | 2 |
| **Engagement** | 40,000 | 25 | 500 | 128 | 3 | 1.5 GB | 2 |
| **Admin** | 15,000 | 15 | 500 | 32 | 3 | 1 GB | 2 |

---

## Detailed Service Specifications

### 1. Gateway (Spring Cloud Gateway / Netty)
**Role**: Edge router, TLS termination, rate limiting, circuit breaking, JWT validation

| Parameter | Value | Notes |
|-----------|-------|-------|
| **Max Throughput** | 200,000+ RPS | Reactive, non-blocking |
| **Latency Budget** | p99 < 10ms (edge only) | Excludes upstream |
| **Concurrent Connections** | 50,000 | Netty event loop |
| **Rate Limit** | 5,000 RPS global burst 10,000 | Per-IP + tier multipliers |
| **Circuit Breakers** | 12 (per downstream service) | Failure threshold 50% |
| **JVM Heap** | 1.5 GB | -XX:MaxRAMPercentage=75 |
| **Off-heap/Netty** | 512 MB | Direct buffers |
| **Thread Pool** | 2x CPU cores | Netty workers |
| **Redis Pool** | 256 connections | Lettuce |
| **Auto-scale Trigger** | CPU > 70%, latency p99 > 50ms | HPA min 3, max 10 |

**Scaling Behavior**:
- Horizontal: Stateless, scale on CPU/latency
- Sticky sessions NOT required (JWT stateless)
- Graceful drain: 90s terminationGracePeriodSeconds

---

### 2. Identity Service
**Role**: Authentication, authorization, user management, tenant management

| Parameter | Value | Notes |
|-----------|-------|-------|
| **Max Throughput** | 30,000 RPS | Login/register ~10%, token validate ~90% |
| **Latency Budget** | p99 < 50ms | Token validation < 10ms |
| **DB Pool (Hikari)** | 20 max, 10 min | Via PgBouncer 500 |
| **Redis Pool** | 64 connections | Session cache, rate limit |
| **JWT Validation** | RS256 via JWKS | Cache TTL 1 hour |
| **JVM Heap** | 1 GB | -XX:MaxRAMPercentage=75 |
| **Auto-scale Trigger** | CPU > 70%, DB pool > 80% | HPA min 2, max 8 |

**Capacity Notes**:
- Token validation is the hot path - uses JWKS cache
- Login rate limited to 100/min/IP
- Password hashing: Argon2id (configurable cost)
- No sticky sessions

---

### 3. Commerce Service (Order + Payment + Delivery)
**Role**: Cart, orders, payments, delivery orchestration

| Parameter | Value | Notes |
|-----------|-------|-------|
| **Max Throughput** | 50,000 RPS | Order create ~5%, cart ~60%, payment ~20% |
| **Latency Budget** | p99 < 200ms | Payment external call adds variance |
| **DB Pool (Hikari)** | 30 max, 15 min | Via PgBouncer 500 |
| **Redis Pool** | 128 connections | Idempotency, cart, rate limit |
| **Kafka Consumers** | 3 (concurrency) | order.events.v1 |
| **JVM Heap** | 2 GB | -XX:MaxRAMPercentage=75 |
| **External Calls** | Razorpay (payment), Delivery partners | Circuit breaker 50% |
| **Auto-scale Trigger** | CPU > 70%, Kafka lag > 1000 | HPA min 3, max 12 |

**Hot Paths**:
1. `POST /cart/add` - Redis + DB (idempotency)
2. `POST /orders` - DB transaction + Kafka publish
3. `POST /payments/initiate` - External Razorpay call

**Capacity Bottlenecks**:
- Payment external latency (Razorpay ~500-2000ms)
- Database write contention on orders table
- Kafka producer backpressure under spike

---

### 4. Catalog Service (Restaurant + Search + Personalization)
**Role**: Restaurant discovery, menu management, search, recommendations

| Parameter | Value | Notes |
|-----------|-------|-------|
| **Max Throughput** | 35,000 RPS | Search ~40%, menu ~40%, personalization ~20% |
| **Latency Budget** | p99 < 150ms | Search can be slower |
| **DB Pool (Hikari)** | 20 max, 10 min | Via PgBouncer 500 |
| **Redis Pool** | 64 connections | Cache, rate limit |
| **Kafka Consumers** | 3 | restaurant.events.v1 |
| **JVM Heap** | 1.5 GB | -XX:MaxRAMPercentage=75 |
| **Search** | Elasticsearch/OpenSearch | Separate cluster |
| **Auto-scale Trigger** | CPU > 70%, Search latency p99 > 300ms | HPA min 2, max 8 |

**Hot Paths**:
1. `GET /restaurants/public/search` - Heavy read, cached
2. `GET /menu/restaurant/{id}` - Menu + pricing
3. `GET /recommendations` - ML model inference

**Capacity Notes**:
- Read-heavy: 90% reads, 10% writes
- Menu items cached in Redis (TTL 5 min)
- Search uses dedicated OpenSearch cluster
- Personalization model served via internal gRPC

---

### 5. Engagement Service (Social + Growth + Referral + Survey + Notification + Realtime)
**Role**: Social features, loyalty, referrals, surveys, notifications, WebSocket/SSE

| Parameter | Value | Notes |
|-----------|-------|-------|
| **Max Throughput** | 40,000 RPS | Notifications ~50%, realtime ~30% |
| **Latency Budget** | p99 < 100ms | WebSocket message < 50ms |
| **DB Pool (Hikari)** | 25 max, 12 min | Via PgBouncer 500 |
| **Redis Pool** | 128 connections | Pub/sub, session, rate limit |
| **Kafka Consumers** | 3 | notification.events.v1 |
| **JVM Heap** | 1.5 GB | -XX:MaxRAMPercentage=75 |
| **WebSocket** | 100k concurrent | Consistent hash SSE |
| **Auto-scale Trigger** | CPU > 70%, WS connections > 80k | HPA min 3, max 10 |

**Hot Paths**:
1. `POST /notifications` - Fire-and-forget, Kafka
2. `GET /api/v1/live/{userId}` - SSE/WebSocket
3. `POST /surveys/submit` - Async processing
4. `POST /referrals/validate` - Redis lookup

**Capacity Notes**:
- SSE connections: 100k per pod (Netty)
- Consistent hashing routes user to same pod
- Notifications are async via Kafka
- Loyalty points: Redis sorted sets

---

### 6. Admin Service (Analytics + Support)
**Role**: Admin dashboards, analytics, support tickets, fraud detection

| Parameter | Value | Notes |
|-----------|-------|-------|
| **Max Throughput** | 15,000 RPS | Low volume, complex queries |
| **Latency Budget** | p99 < 500ms | Analytics queries can be slow |
| **DB Pool (Hikari)** | 15 max, 8 min | Via PgBouncer 500 |
| **Redis Pool** | 32 connections | Cache, rate limit |
| **Kafka Consumers** | 3 | admin.events.v1 |
| **JVM Heap** | 1 GB | -XX:MaxRAMPercentage=75 |
| **Read Replica** | Enabled | Analytics queries on replica |
| **Auto-scale Trigger** | CPU > 70%, Query queue > 50 | HPA min 2, max 6 |

**Capacity Notes**:
- Read-heavy analytics on read replica
- Support tickets: low volume, high complexity
- Fraud detection: async ML scoring
- No sticky sessions

---

## Infrastructure Capacity

### PostgreSQL (Primary)
| Parameter | Value |
|-----------|-------|
| **Instance** | 1 primary + 2 replicas |
| **Instance Class** | db.r6g.2xlarge (8 vCPU, 64 GB) |
| **Max Connections** | 5,000 (via PgBouncer) |
| **Shared Buffers** | 16 GB (25% RAM) |
| **Effective Cache** | 48 GB (75% RAM) |
| **Storage** | 2 TB gp3 (10k IOPS) |
| **Backup** | Continuous + daily snapshots |
| **Read Replicas** | 2 (async, < 100ms lag) |

### PgBouncer (Transaction Pooling)
| Parameter | Value |
|-----------|-------|
| **Instances** | 2 (HA) |
| **Pool Mode** | Transaction |
| **Max Client Connections** | 10,000 per instance |
| **Default Pool Size** | 500 per database |
| **Min Pool Size** | 100 per database |
| **Reserve Pool** | 50 per database |

### Redis (Sentinel HA)
| Parameter | Value |
|-----------|-------|
| **Topology** | 1 master + 2 replicas + 3 sentinels |
| **Instance Class** | cache.r6g.xlarge (4 vCPU, 32 GB) |
| **Max Memory** | 24 GB (75% RAM) |
| **Maxmemory Policy** | volatile-lru |
| **Persistence** | AOF everysec |
| **Sentinel Quorum** | 2 of 3 |

### Kafka (MSK / Self-managed)
| Parameter | Value |
|-----------|-------|
| **Brokers** | 6 (3 AZs) |
| **Instance Class** | kafka.m5.2xlarge (8 vCPU, 32 GB) |
| **Partitions per Topic** | 24 (6 brokers × 4) |
| **Replication Factor** | 3 |
| **Retention** | 7 days / 500 GB |
| **Consumer Groups** | 6 services × 3 concurrency = 18 |

---

## Auto-Scaling Policies

### HPA Configuration (per service)
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {service}-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: bhukkad-{service}
  minReplicas: {min}
  maxReplicas: {max}
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Pods
    pods:
      metric:
        name: http_requests_per_second
      target:
        type: AverageValue
        averageValue: "5000"
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
      - type: Percent
        value: 10
        periodSeconds: 60
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
      - type: Percent
        value: 100
        periodSeconds: 15
      - type: Pods
        value: 4
        periodSeconds: 15
      selectPolicy: Max
```

---

## Breaking Points & Degradation Modes

| Service | Breaking Point | Degradation Mode |
|---------|----------------|------------------|
| Gateway | 250k RPS | 503 on rate limit, circuit breakers open |
| Identity | 40k RPS | Token validation slow, login queue |
| Commerce | 70k RPS | Payment timeout, order queue builds |
| Catalog | 50k RPS | Search falls back to cache-only |
| Engagement | 60k RPS | Notification delay, SSE disconnect |
| Admin | 20k RPS | Query timeout, read replica lag |

---

## Cost Estimation (Monthly, AWS us-east-1)

| Component | Monthly Cost (USD) |
|-----------|-------------------|
| EKS (6 services × 4-10 pods) | ~$8,000 |
| RDS PostgreSQL (primary + 2 replicas) | ~$4,500 |
| ElastiCache Redis (3 nodes + 3 sentinels) | ~$2,500 |
| MSK Kafka (6 brokers) | ~$3,500 |
| OpenSearch (3 nodes) | ~$1,800 |
| ALB / NLB / Route53 | ~$500 |
| Data Transfer / CloudWatch | ~$1,200 |
| **Total** | **~$22,000/month** |

---

## Capacity Planning Triggers

| Trigger | Action |
|---------|--------|
| Sustained CPU > 70% for 10 min | Scale out + investigate |
| p99 latency > 2x baseline | Scale out + check dependencies |
| DB pool usage > 80% | Increase PgBouncer pool / read replica |
| Redis memory > 85% | Increase maxmemory / evict policy |
| Kafka lag > 10k | Increase consumer concurrency |
| Error rate > 0.5% | Circuit breaker review / rollback |

---

## Testing Validation

Before go-live, validate:
- [ ] Load test at 150% target (300k RPS gateway)
- [ ] Soak test 4 hours at 100% target
- [ ] Failover test (kill each dependency)
- [ ] Chaos test (latency injection, pod kill)
- [ ] Data consistency under load
- [ ] Recovery time objectives (RTO < 5 min)