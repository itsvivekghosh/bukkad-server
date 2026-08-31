# Bhukkad Operations Guide

This document provides operational guidance for deploying, monitoring, and maintaining the Bhukkad Food Delivery System in production.

## Table of Contents

- [Deployment](#deployment)
- [Configuration](#configuration)
- [Monitoring](#monitoring)
- [Health Checks](#health-checks)
- [Scaling](#scaling)
- [Backup & Recovery](#backup--recovery)
- [Troubleshooting](#troubleshooting)

---

## Deployment

### Docker Deployment

#### Build Image
```bash
docker build -f docker/Dockerfile -t bhukkad:latest .
```

#### Run with Docker Compose
```bash
docker compose -f docker/docker-compose.prod.yml up -d
```

#### Environment Variables
Required production environment variables:
- `DB_HOST` — MySQL host
- `DB_PORT` — MySQL port (default: 3306)
- `DB_USERNAME` — Database username
- `DB_PASSWORD` — Database password
- `REDIS_HOST` — Redis host
- `REDIS_PORT` — Redis port (default: 6379)
- `JWT_SECRET` — Base64-encoded JWT signing key (min 64 bytes)
- `RAZORPAY_KEY_ID` — Payment gateway key
- `RAZORPAY_KEY_SECRET` — Payment gateway secret

### Kubernetes Deployment

```bash
kubectl apply -f k8s/
```

Key Kubernetes resources:
- Deployment: 10 replicas, resource limits 2CPU/4GB
- Service: ClusterIP for internal, LoadBalancer for external
- HPA: Scale based on CPU (70%) and memory (80%)
- ConfigMap: Application configuration
- Secret: Sensitive credentials

---

## Configuration

### Application Profiles

| Profile | Purpose |
|---------|---------|
| `dev` | Local development with debug logging |
| `prod` | Production with optimized settings |

### Key Configuration Properties

#### Database
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30
      minimum-idle: 10
      connection-timeout: 10000
```

#### Redis
```yaml
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 40
          max-idle: 20
```

#### Cache TTLs
```yaml
cache:
  ttl:
    restaurant: 3600
    menu-item: 1800
    order: 300
```

---

## Monitoring

### Health Endpoints

| Endpoint | Purpose |
|----------|---------|
| `/actuator/health` | Overall health status |
| `/actuator/health/liveness` | Liveness probe |
| `/actuator/health/readiness` | Readiness probe |
| `/actuator/metrics` | Application metrics |
| `/actuator/prometheus` | Prometheus metrics |

### Key Metrics to Monitor

- **Request Rate**: Requests per second by endpoint
- **Error Rate**: 4xx and 5xx response rates
- **Latency**: P50, P95, P99 response times
- **Database**: Connection pool utilization, query time
- **Redis**: Memory usage, hit rate, connected clients
- **JVM**: GC pause times, heap usage, thread count

### Alerts

| Alert | Threshold | Severity |
|-------|-----------|----------|
| High Error Rate | > 5% 5xx responses | Critical |
| High Latency | P99 > 2s | Warning |
| DB Connections | > 80% pool utilization | Warning |
| Redis Memory | > 85% usage | Warning |
| Disk Space | < 20% free | Critical |

---

## Health Checks

### Liveness Probe
```bash
curl -f http://localhost:8080/actuator/health/liveness
```

### Readiness Probe
```bash
curl -f http://localhost:8080/actuator/health/readiness
```

### Database Health
```bash
curl -f http://localhost:8080/actuator/health/db
```

### Redis Health
```bash
curl -f http://localhost:8080/actuator/health/redis
```

---

## Scaling

### Vertical Scaling
- Increase pod CPU/memory for higher throughput
- Recommended: 2 CPU cores, 4GB RAM per pod
- Max recommended: 4 CPU cores, 8GB RAM per pod

### Horizontal Scaling
- Scale pods based on request rate
- 1 pod ≈ 500 RPS sustained
- Recommended: 10 pods for 5k RPS

### Database Scaling
- Read replicas for read-heavy workloads
- Connection pool: 30 per pod × 10 pods = 300 max
- MySQL max_connections: 500 recommended

### Cache Scaling
- Redis Cluster for > 100k keys
- Memory: 4GB minimum, 16GB recommended
- Persistence: RDB snapshots every 6 hours

---

## Backup & Recovery

### Database Backups
```bash
# Daily backup
mysqldump -h localhost -u root -p bhukkad > backup_$(date +%Y%m%d).sql

# Restore
mysql -h localhost -u root -p bhukkad < backup_20260830.sql
```

### Redis Backups
```bash
# RDB snapshot (automatic via Redis config)
redis-cli BGSAVE

# AOF (append-only file) for point-in-time recovery
redis-cli CONFIG SET appendonly yes
```

### Disaster Recovery
- Database: Daily automated backups, 30-day retention
- Redis: RDB snapshots every 6 hours
- Application state: Stateless, no local persistence

---

## Troubleshooting

### Common Issues

#### High Memory Usage
1. Check heap dump: `jmap -dump:format=b,file=heap.hprof <pid>`
2. Analyze with Eclipse MAT
3. Look for memory leaks in cache or session storage

#### Slow Database Queries
1. Enable query logging: `spring.jpa.properties.hibernate.format_sql=true`
2. Check slow query log in MySQL
3. Use `EXPLAIN` to analyze query plans

#### Redis Connection Issues
1. Check Redis memory: `redis-cli INFO memory`
2. Check connected clients: `redis-cli INFO clients`
3. Verify network connectivity from app pods

#### JWT Token Errors
1. Verify `JWT_SECRET` is set and valid
2. Check token expiration in logs
3. Ensure clock synchronization across servers

### Log Locations

| Component | Log Location |
|-----------|-------------|
| Application | `/var/log/bhukkad/app.log` |
| MySQL | `/var/log/mysql/error.log` |
| Redis | `/var/log/redis/redis-server.log` |
| Nginx | `/var/log/nginx/access.log` |

### Emergency Procedures

#### Rollback Deployment
```bash
kubectl rollout undo deployment/bhukkad -n production
```

#### Database Maintenance
```bash
# Read-only mode
kubectl set env deployment/bhukkad DB_READ_ONLY=true

# Restart pods
kubectl rollout restart deployment/bhukkad -n production
```

#### Scale Down for Maintenance
```bash
kubectl scale deployment/bhukkad --replicas=2 -n production
```

---

## Security

### Secrets Management
- All secrets stored in Kubernetes Secrets or Vault
- Never commit secrets to version control
- Rotate JWT_SECRET quarterly

### Network Security
- TLS 1.3 for all external traffic
- Internal communication via mTLS
- WAF rules for common attack patterns

### Access Control
- RBAC for Kubernetes access
- Least privilege for service accounts
- Audit logging for all admin actions

---

## Performance Tuning

### JVM Tuning
```bash
-Xms2g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

### Database Tuning
- `innodb_buffer_pool_size`: 70% of available RAM
- `max_connections`: 500
- `query_cache_type`: 0 (disabled for MySQL 8.0)

### Redis Tuning
- `maxmemory`: 4GB
- `maxmemory-policy`: allkeys-lru
- `save`: 900 1 300 10 60 10000

---

## Contact

For production issues:
- **PagerDuty**: bhukkad-oncall
- **Slack**: #bhukkad-ops
- **Email**: ops@bhukkad.com
