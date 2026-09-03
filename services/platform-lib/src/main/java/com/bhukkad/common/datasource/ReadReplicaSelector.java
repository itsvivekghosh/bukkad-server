package com.bhukkad.common.datasource;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robins replica selection and tracks health cooldown (port of
 * {@code com.bhukkad.datasource.ReadReplicaSelector}). When no replicas are
 * configured, the lookup key resolves to PRIMARY.
 */
public class ReadReplicaSelector {

    private final List<String> replicas;
    private final AtomicInteger counter = new AtomicInteger();
    private final ConcurrentMap<String, Long> unavailableUntil = new ConcurrentHashMap<>();

    public ReadReplicaSelector(List<String> replicas) {
        this.replicas = List.copyOf(replicas);
    }

    public Object next() {
        if (replicas.isEmpty()) {
            // Legacy single-replica behaviour: no explicit replica list means a
            // read-only transaction routes to the REPLICA target key (which the
            // routing datasource resolves to the configured read datasource).
            return ReadReplicaType.REPLICA;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < replicas.size(); i++) {
            String candidate = replicas.get(Math.floorMod(counter.getAndIncrement(), replicas.size()));
            Long cooldown = unavailableUntil.get(candidate);
            if (cooldown == null || cooldown < now) {
                return candidate;
            }
        }
        return ReadReplicaType.REPLICA;
    }

    public void markUnavailable(String replicaKey) {
        // 10s cooldown before a replica is retried.
        unavailableUntil.put(replicaKey, System.currentTimeMillis() + 10_000);
    }
}
