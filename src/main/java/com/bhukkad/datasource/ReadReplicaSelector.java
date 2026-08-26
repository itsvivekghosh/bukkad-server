package com.bhukkad.datasource;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robins read connections across the configured read replicas so no
 * single replica absorbs all read traffic, and skips replicas that have
 * recently failed to serve a connection (health-aware failover).
 *
 * <p>Thread-safe via an atomic counter; {@code Math.floorMod} keeps the index
 * in range even if the counter overflows. A replica marked unavailable (via
 * {@link #markUnavailable(Object)}) is skipped for the configured cooldown
 * window; {@code next()} still returns a round-robin pick when every replica is
 * in cooldown rather than throwing, so a degraded-but-alive fleet keeps
 * serving reads instead of failing the request outright.
 */
public class ReadReplicaSelector {

    private static final long DEFAULT_UNAVAILABLE_COOLDOWN_MS = 30_000L;

    private final List<Object> replicaKeys;
    private final AtomicInteger counter = new AtomicInteger();
    private final long unavailableCooldownMs;
    private final ConcurrentHashMap<Object, Long> unavailableUntil = new ConcurrentHashMap<>();

    public ReadReplicaSelector(List<Object> replicaKeys) {
        this(replicaKeys, DEFAULT_UNAVAILABLE_COOLDOWN_MS);
    }

    public ReadReplicaSelector(List<Object> replicaKeys, long unavailableCooldownMs) {
        this.replicaKeys = replicaKeys == null ? List.of() : List.copyOf(replicaKeys);
        this.unavailableCooldownMs = Math.max(0, unavailableCooldownMs);
    }

    /**
     * Returns the next healthy replica lookup key, or the default
     * single-replica key ({@link ReadReplicaType#REPLICA}) when no replica list
     * is configured. Replicas in the unavailable cooldown window are skipped.
     */
    public Object next() {
        if (replicaKeys.isEmpty()) {
            return ReadReplicaType.REPLICA;
        }
        for (int attempt = 0; attempt < replicaKeys.size(); attempt++) {
            Object candidate = replicaKeys.get(Math.floorMod(counter.getAndIncrement(), replicaKeys.size()));
            if (isHealthy(candidate)) {
                return candidate;
            }
        }
        // Every replica is in cooldown: serve the next round-robin pick rather
        // than failing reads while a degraded fleet recovers.
        return replicaKeys.get(Math.floorMod(counter.get(), replicaKeys.size()));
    }

    /** Marks a replica unavailable for the cooldown window after a connection failure. */
    public void markUnavailable(Object key) {
        if (key != null) {
            unavailableUntil.put(key, System.currentTimeMillis() + unavailableCooldownMs);
        }
    }

    /** Clears a replica's cooldown (e.g., after a successful reconnection). */
    public void markAvailable(Object key) {
        if (key != null) {
            unavailableUntil.remove(key);
        }
    }

    public int replicaCount() {
        return replicaKeys.size();
    }

    private boolean isHealthy(Object key) {
        Long until = unavailableUntil.get(key);
        return until == null || until <= System.currentTimeMillis();
    }
}
