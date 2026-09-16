# Kafka Consumer Lag Runbook

**Alert:** `HighConsumerLag` — `kafka_consumer_group_lag > 1000` for 5 minutes.

## Symptoms
- Order events, platform events, or delivery events are not being processed in real-time
- Search index is stale (menu items not updated)
- Notification delivery is delayed
- Dashboard metrics show increasing lag on consumer groups

## Immediate Actions
1. **Identify the lagging consumer group:**
   ```bash
   kubectl exec -it <kafka-pod> -n bhukkad -- kafka-consumer-groups.sh \
       --bootstrap-server localhost:9092 \
       --describe --group <consumer-group>
   ```

2. **Check consumer pod health:**
   ```bash
   kubectl get pods -l app=bhukkad -n bhukkad | grep -E "order|notification|search|delivery"
   kubectl describe pod <consumer-pod> -n bhukkad
   kubectl logs <consumer-pod> -n bhukkad --tail=100
   ```

3. **Check for stuck processing:**
   ```bash
   # Look for long-running poll loops or thread pool exhaustion
   kubectl logs <consumer-pod> -n bhukkad | grep -E "WARN|ERROR|timeout"
   ```

4. **Restart consumer if stuck:**
   ```bash
   kubectl rollout restart deployment/<consumer-service> -n bhukkad
   ```

5. **Verify lag reduction:**
   ```bash
   kubectl exec -it <kafka-pod> -n bhukkad -- kafka-consumer-groups.sh \
       --bootstrap-server localhost:9092 \
       --describe --group <consumer-group>
   ```

## Common Causes
| Cause | Symptom | Fix |
|-------|---------|-----|
| Consumer pod OOMKilled | Pod restarts frequently | Increase memory limit |
| Slow downstream DB | Consumer logs show slow queries | Tune DB queries, add indexes |
| Poison message | Consumer stuck on one message | Check DLQ (`*.dlt` topic), reprocess or discard |
| Network partition | Consumer cannot reach Kafka | Check network policies |
| Batch size too large | Consumer timeout on `max-poll-interval` | Reduce `max-poll-records` or increase `max-poll-interval-ms` |

## Kafka Consumer Configs (current)
```yaml
max-poll-records: 500
session-timeout-ms: 30000
heartbeat-interval-ms: 10000
max-poll-interval-ms: 300000
```

## Prevention
- Consumer lag alerting via Prometheus (already configured)
- DLQ topics for poison messages (already configured)
- Dead-letter queue replay script (`scripts/ci/replay-dlq.sh`)
- Outbox relay with idempotent consumer (prevents duplicate processing)

## Escalation
- If lag > 100k messages, consider increasing consumer replicas
- If data loss occurred, replay from outbox table or DLQ
