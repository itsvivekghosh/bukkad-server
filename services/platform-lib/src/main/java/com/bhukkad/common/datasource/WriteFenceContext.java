package com.bhukkad.common.datasource;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Write-fence context to prevent stale reads from read replicas.
 *
 * <p>After a write operation, reads are pinned to the primary for TTL
 * to avoid reading stale data from read replicas that may be lagging.</p>
 *
 * <p>A pluggable clock (via {@link #setClock(LongSupplier)}) is supported for
 * testing; {@link #resetClock()} restores the system clock.</p>
 */
public class WriteFenceContext {

    /** Default write-fence TTL: 2 seconds. */
    public static final long DEFAULT_WRITE_FENCE_MS = 2000L;

    private static volatile long writeFenceTtlMs = DEFAULT_WRITE_FENCE_MS;

    /** Pluggable clock for testing; null means use System.nanoTime(). */
    private static volatile LongSupplier nanoClock = null;

    /**
     * Sets a pluggable nanosecond clock for deterministic testing.
     * Pass {@code null} to restore {@link System#nanoTime()}.
     */
    public static void setClock(LongSupplier clock) {
        nanoClock = clock;
    }

    /** Restores the system nanosecond clock. */
    public static void resetClock() {
        nanoClock = null;
    }

    private static long nowNanos() {
        if (nanoClock != null) {
            // Test clock provides millisecond values; convert to nanoseconds
            return nanoClock.getAsLong() * 1_000_000L;
        }
        return System.nanoTime();
    }

    /**
     * Arms (activates) the write fence for the current thread using the
     * current time. Equivalent to {@link #setWriteTime(Instant.now())}.
     */
    public static void arm() {
        setWriteTime(Instant.now());
    }

    /**
     * Sets the write-fence TTL in milliseconds. Non-positive values are ignored
     * (the previous TTL is retained).
     */
    public static void setWriteFenceMs(long writeFenceMs) {
        if (writeFenceMs > 0) {
            writeFenceTtlMs = writeFenceMs;
        }
    }

    /**
     * Returns the current write-fence TTL in milliseconds.
     */
    public static long getWriteFenceMs() {
        return writeFenceTtlMs;
    }

    private static final ThreadLocal<Fence> activeFence = new ThreadLocal<>();
    private static final AtomicReference<Instant> lastWrite = new AtomicReference<>(Instant.MIN);

    /**
     * Sets the write fence for the current thread. After calling this,
     * {@link #isActive()} returns true for the configured TTL.
     */
    public static void setWriteTime(Instant writeTime) {
        activeFence.set(new Fence(writeTime, nowNanos() + writeFenceTtlMs * 1_000_000L));
        lastWrite.set(writeTime);
    }

    /**
     * Returns true if a write fence is active on the current thread (within
     * the TTL window after a write).
     */
    public static boolean isActive() {
        Fence fence = activeFence.get();
        if (fence == null) {
            return false;
        }
        if (nowNanos() >= fence.expiresAt) {
            remove();
            return false;
        }
        return true;
    }

    public static Instant getLastWriteTime() {
        return lastWrite.get();
    }

    public static void clear() {
        activeFence.remove();
    }

    private static void remove() {
        activeFence.remove();
    }

    private static final class Fence {
        final Instant writeTime;
        final long expiresAt;

        Fence(Instant writeTime, long expiresAt) {
            this.writeTime = writeTime;
            this.expiresAt = expiresAt;
        }
    }
}
