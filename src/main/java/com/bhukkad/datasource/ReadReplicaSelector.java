package com.bhukkad.datasource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robins read connections across the configured read replicas so no
 * single replica absorbs all read traffic. Thread-safe via an atomic counter;
 * {@code Math.floorMod} keeps the index in range even if the counter overflows.
 */
public class ReadReplicaSelector {

    private final List<Object> replicaKeys;
    private final AtomicInteger counter = new AtomicInteger();

    public ReadReplicaSelector(List<Object> replicaKeys) {
        this.replicaKeys = replicaKeys == null ? List.of() : List.copyOf(replicaKeys);
    }

    /**
     * Returns the next replica lookup key, or the default single-replica key
     * ({@link ReadReplicaType#REPLICA}) when no replica list is configured.
     */
    public Object next() {
        if (replicaKeys.isEmpty()) {
            return ReadReplicaType.REPLICA;
        }
        return replicaKeys.get(Math.floorMod(counter.getAndIncrement(), replicaKeys.size()));
    }

    public int replicaCount() {
        return replicaKeys.size();
    }
}
