# Redis Down Runbook

**Alert:** `RedisDown` or `ThresholdedRedisHealthIndicator` reports `OUT_OF_SERVICE` for 3 consecutive checks.

## Symptoms
- Identity service returns 503 on `/api/v1/auth/login` and `/api/v1/auth/refresh`
- Edge rate-limit buckets stop working; traffic bypasses rate limits (fail-open)
- Edge cache misses spike; downstream services see increased DB load
- Circuit breakers may open if Redis-dependent calls time out

## Immediate Actions
1. **Check Redis pod status:**
   ```bash
   kubectl get pods -l app=redis -n bhukkad
   kubectl describe pod <redis-pod> -n bhukkad
   kubectl logs <redis-pod> -n bhukkad --tail=100
   ```

2. **Check Redis persistence:**
   ```bash
   kubectl exec -it <redis-pod> -n bhukkad -- redis-cli LASTSAVE
   kubectl exec -it <redis-pod> -n bhukkad -- redis-cli INFO persistence
   ```

3. **Restart Redis if pod is crash-looping:**
   ```bash
   kubectl rollout restart statefulset/redis -n bhukkad
   ```

4. **Verify recovery:**
   ```bash
   kubectl get pods -l app=redis -n bhukkad -w
   kubectl exec -it <redis-pod> -n bhukkad -- redis-cli PING
   ```

5. **Verify identity service recovery:**
   ```bash
   curl -f http://identity:8081/actuator/health/redis
   curl -f http://identity:8081/actuator/health/readiness
   ```

## Root Cause Analysis
- **OOMKilled:** Increase Redis memory limit (`resources.limits.memory`)
- **Disk full:** Check PVC usage (`kubectl exec -it <redis-pod> -- df -h`)
- **Network partition:** Check network policies and service mesh
- **Config error:** Review recent Redis config changes

## Prevention
- Redis sentinel/cluster for HA (already configured in application.yml)
- `ThresholdedRedisHealthIndicator` prevents readiness flaps on micro-hiccups
- Cache-invalidation listener retries forever (no crash-loop on Redis restart)
- Daily RDB backups to S3 (see `backup-and-restore-runbook.md`)

## Escalation
- If Redis is unrecoverable, fail over to sentinel/cluster
- If data loss occurred, restore from latest S3 backup
