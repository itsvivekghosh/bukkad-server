#!/usr/bin/env bash
set -euo pipefail

BROKERS="redis-cluster-0.redis-cluster-headless.bhukkad.svc.cluster.local:6379 \
         redis-cluster-1.redis-cluster-headless.bhukkad.svc.cluster.local:6379 \
         redis-cluster-2.redis-cluster-headless.bhukkad.svc.cluster.local:6379"

echo "Creating Redis cluster with 3 masters and 3 replicas..."
redis-cli --cluster create $BROKERS --cluster-replicas 1 --no-auth-warning <<< "yes"

echo "Verifying cluster state..."
redis-cli -h redis-cluster-0 cluster info | grep cluster_state

echo "Assigning slot ranges by use-case..."
redis-cli -h redis-cluster-0 cluster add-slots $(seq 0 3999)      # cache:*
redis-cli -h redis-cluster-1 cluster add-slots $(seq 4000 6999)   # ratelimit:*
redis-cli -h redis-cluster-2 cluster add-slots $(seq 7000 8999)   # outbox:wake:*
redis-cli -h redis-cluster-0 cluster add-slots $(seq 9000 10999)  # live:*
redis-cli -h redis-cluster-0 cluster add-slots $(seq 11000 16383) # idempotency:*

echo "Cluster bootstrap complete:"
redis-cli -h redis-cluster-0 cluster nodes
