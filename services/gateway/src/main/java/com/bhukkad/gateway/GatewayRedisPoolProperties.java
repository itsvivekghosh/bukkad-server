package com.bhukkad.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis client-pool sizing for the gateway edge (audit R-07/R-11 blast-radius
 * split). ONE Redis, TWO Lettuce {@link
 * io.lettuce.core.resource.ClientResources} pools:
 *
 * <ul>
 *   <li><b>shared</b> — the request-path pool: per-request limiter buckets
 *       ({@code EdgeRateLimitFilter}) and the periodic feature-flag hash
 *       refresh. Sized for the edge's reactive thread geometry;</li>
 *   <li><b>sse-relay</b> — a deliberately small, dedicated pool for the
 *       long-lived pub/sub relay connections ({@code EdgeFeatureFlags}
 *       invalidation subscription driving the live/SSE route kill switches).
 *       A wedged subscriber connection must never starve — or be starved by —
 *       the request path.</li>
 * </ul>
 *
 * Both pools clamp at Lettuce's minimum of 2 threads. Keys are env-wired from
 * bhukkad-config (REDIS_POOL_* — see k8s/configmap.yaml R-11 notes).
 */
@ConfigurationProperties(prefix = "app.redis.pool")
public class GatewayRedisPoolProperties {

    private final Pool shared = new Pool();
    private final Pool sseRelay = new Pool();

    public Pool getShared() {
        return shared;
    }

    public Pool getSseRelay() {
        return sseRelay;
    }

    public static class Pool {

        /** Lettuce event-loop (I/O) threads; clamped to >= 2. */
        private int ioThreads = 4;

        /** Task-dispatch threads for completions; clamped to >= 2. */
        private int computationThreads = 4;

        public int getIoThreads() {
            return ioThreads;
        }

        public void setIoThreads(int ioThreads) {
            this.ioThreads = ioThreads;
        }

        public int getComputationThreads() {
            return computationThreads;
        }

        public void setComputationThreads(int computationThreads) {
            this.computationThreads = computationThreads;
        }
    }
}
