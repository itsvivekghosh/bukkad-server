package com.bhukkad.common.datasource;

import java.util.function.LongSupplier;

/**
 * V-17 thread-local write fence (read-your-writes across the replica lag
 * budget): after a primary write commits on a thread, reads on that same
 * thread are pinned to the primary while the fence age is below the
 * configured TTL — even when {@link ReadReplicaContext} or the transaction's
 * read-only flag would route them to a replica.
 *
 * <p>The fence only <strong>upgrades reads to the primary</strong>; it never
 * routes a write to a replica (the write path already resolves PRIMARY and the
 * replica pools are read-only). Staleness window: the fence expires silently
 * once its age exceeds the TTL, restoring replica offload.</p>
 *
 * <p>Static context mirrors {@link ReadReplicaContext} (ambient per-thread
 * state shared by the routing datasource), because the routing datasource
 * instance is constructed inside {@code DataSourceConfig} and wrapped in a
 * {@code LazyConnectionDataSourceProxy} — it is not itself a Spring bean and
 * therefore cannot receive per-instance property injection.</p>
 *
 * <p>TTL source: {@code app.datasource.replica.write-fence-ms} (default
 * 2000 ms), applied by {@link ReplicaRoutingConfig} at startup. The clock is
 * injectable so tests can drive fence expiry deterministically with a fake
 * clock.</p>
 */
public final class WriteFenceContext {

    /** Default staleness budget: {@code app.datasource.replica.write-fence-ms} when unset. */
    static final long DEFAULT_WRITE_FENCE_MS = 2000L;

    private static final ThreadLocal<Long> FENCED_AT_MS = new ThreadLocal<>();

    private static volatile long writeFenceMs = DEFAULT_WRITE_FENCE_MS;
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private WriteFenceContext() {
    }

    /**
     * Arms (or refreshes) the fence for the current thread at the current
     * clock instant. Called from the routing datasource's
     * {@code TransactionSynchronization.afterCommit} after a primary write
     * transaction commits.
     */
    static void arm() {
        FENCED_AT_MS.set(clock.getAsLong());
    }

    /** True while a committed primary write on this thread is inside its TTL window. */
    public static boolean isActive() {
        Long fencedAt = FENCED_AT_MS.get();
        return fencedAt != null && clock.getAsLong() - fencedAt < writeFenceMs;
    }

    /** Removes the fence from the current thread (test hygiene / explicit reset). */
    static void clear() {
        FENCED_AT_MS.remove();
    }

    static void setWriteFenceMs(long fenceMs) {
        if (fenceMs > 0) {
            writeFenceMs = fenceMs;
        }
    }

    static long getWriteFenceMs() {
        return writeFenceMs;
    }

    /** Test seam: deterministic clock. Callers must restore via {@link #resetClock()}. */
    static void setClock(LongSupplier fakeClock) {
        clock = fakeClock;
    }

    static void resetClock() {
        clock = System::currentTimeMillis;
    }
}
