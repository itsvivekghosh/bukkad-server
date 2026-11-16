package com.bhukkad.common.cache;

import java.lang.reflect.Method;
import java.util.Optional;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spring AOP aspect that provides {@code @NegativeCacheable} negative caching.
 *
 * <p>On a cache miss that yields an empty result, the aspect stores a
 * {@link NegativeCacheMarker} in Redis for the configured TTL. Subsequent
 * calls within that window return empty without re-invoking the method, so
 * repeated lookups for a genuinely missing entity do not hit the database.</p>
 *
 * <p>This reuses the existing {@link RedisCacheService}/{@link LocalCacheService}
 * primitives (the same wiring as {@link CacheAspect}) and does not introduce a
 * parallel cache system.</p>
 */
@Aspect
@Component
public class NegativeCacheAspect {

    private static final Logger log = LoggerFactory.getLogger(NegativeCacheAspect.class);
    private static final String NEGATIVE_KEY_PREFIX = "neg:";

    private final ObjectProvider<RedisCacheService> cacheProvider;
    private final NegativeCacheProperties properties;

    public NegativeCacheAspect(ObjectProvider<RedisCacheService> cacheProvider,
                               NegativeCacheProperties properties) {
        this.cacheProvider = cacheProvider;
        this.properties = properties;
    }

    /**
     * Intercepts methods annotated with {@code @NegativeCacheable} and serves
     * a cached empty result when a negative marker is present, otherwise
     * computes and caches empty results for the configured TTL.
     */
    @Around("@annotation(com.bhukkad.common.cache.NegativeCacheable)")
    public Object negativeCacheable(ProceedingJoinPoint pjp) throws Throwable {
        NegativeCacheable ann = getAnnotation(pjp);
        if (ann == null || !properties.isEnabled()) {
            return pjp.proceed();
        }

        RedisCacheService cache = cacheProvider.getIfAvailable();
        if (cache == null) {
            return pjp.proceed();
        }

        String key = buildKey(pjp, ann);
        String negKey = NEGATIVE_KEY_PREFIX + key;
        long negativeTtl = ann.ttlSeconds() > 0 ? ann.ttlSeconds() : properties.getTtlSeconds();

        try {
            // L2 negative marker: a repeated miss short-circuits without invoking the method.
            if (cache.exists(negKey)) {
                log.debug("CACHE_NEGATIVE_HIT key={} ttlSeconds={}", key, negativeTtl);
                return emptyForReturnType(getMethod(pjp));
            }

            Object result;
            try {
                result = pjp.proceed();
            } catch (Throwable ex) {
                throw new RuntimeException(ex);
            }

            if (isEmpty(result)) {
                cache.set(negKey, NegativeCacheMarker.INSTANCE, negativeTtl);
                log.debug("CACHE_NEGATIVE_SET key={} ttlSeconds={}", key, negativeTtl);
            }
            return result;
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause != null && cause instanceof Throwable) {
                throw (Throwable) cause;
            }
            throw e;
        } catch (Exception e) {
            // Fail open: if the negative-cache machinery itself fails, call the method directly.
            log.warn("CACHE_NEGATIVE_FAILED key={} error={}", key, e.getMessage());
            return pjp.proceed();
        }
    }

    private NegativeCacheable getAnnotation(ProceedingJoinPoint pjp) {
        for (java.lang.annotation.Annotation a : pjp.getTarget().getClass().getAnnotations()) {
            if (a instanceof NegativeCacheable) {
                return (NegativeCacheable) a;
            }
        }
        Method method = getMethod(pjp);
        if (method != null) {
            return method.getAnnotation(NegativeCacheable.class);
        }
        return null;
    }

    private Method getMethod(ProceedingJoinPoint pjp) {
        try {
            Method[] methods = pjp.getTarget().getClass().getMethods();
            String name = pjp.getSignature().getName();
            for (Method m : methods) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildKey(ProceedingJoinPoint pjp, NegativeCacheable ann) {
        StringBuilder sb = new StringBuilder();
        if (ann.cacheName() != null && !ann.cacheName().isEmpty()) {
            sb.append(ann.cacheName()).append(":");
        }
        sb.append(pjp.getTarget().getClass().getSimpleName()).append(".");
        sb.append(pjp.getSignature().getName()).append(":");
        if (pjp.getArgs() != null) {
            for (int i = 0; i < pjp.getArgs().length; i++) {
                if (i > 0) {
                    sb.append(":");
                }
                sb.append(pjp.getArgs()[i] != null ? pjp.getArgs()[i].toString() : "null");
            }
        }
        return sb.toString();
    }

    private static boolean isEmpty(Object result) {
        if (result == null) {
            return true;
        }
        if (result instanceof Optional<?> optional) {
            return optional.isEmpty();
        }
        if (result instanceof java.util.Collection<?> collection) {
            return collection.isEmpty();
        }
        if (result.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(result) == 0;
        }
        return false;
    }

    private static Object emptyForReturnType(Method method) {
        if (method == null) {
            return null;
        }
        Class<?> returnType = method.getReturnType();
        if (Optional.class.isAssignableFrom(returnType)) {
            return Optional.empty();
        }
        return null;
    }
}