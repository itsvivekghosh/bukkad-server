package com.bhukkad.common.cache;

import java.io.Serializable;

/**
 * Sentinel value stored in L1 (Caffeine) and L2 (Redis) when a cache-aside
 * lookup genuinely returns an empty result. It is intentionally NOT a
 * business value: callers reading the key via {@code get(key, type)} will
 * always see an empty {@link java.util.Optional} because no type matches a
 * {@link NegativeCacheMarker}, so the negative entry is invisible to typed
 * reads and only the dedicated negative-cache path honours it.
 *
 * <p>Serializable so the Spring {@code RedisTemplate} (which defaults to the
 * JdkSerializationRedisSerializer for object values) can round-trip it into
 * Redis without any custom serializer configuration.</p>
 */
public final class NegativeCacheMarker implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Singleton marker — equality is irrelevant; identity is enough. */
    public static final NegativeCacheMarker INSTANCE = new NegativeCacheMarker();

    private NegativeCacheMarker() {
    }

    public static NegativeCacheMarker instance() {
        return INSTANCE;
    }
}