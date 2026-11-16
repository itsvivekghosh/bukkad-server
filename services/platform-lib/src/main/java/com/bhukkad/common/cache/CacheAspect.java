package com.bhukkad.common.cache;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spring AOP aspect that provides transparent {@code @Cacheable} caching for
 * business services.
 *
 * <p>Usage:
 * <pre>{@code
 * @Cacheable(key = "'order:' + #orderId", ttl = 30)
 * public OrderResponse getOrder(Long orderId) { ... }
 * }</pre>
 *
 * <p>OPT-026/027: Eliminates cache logic duplication across services and
 * removes cache abstraction leak from business methods.
 */
@Aspect
@Component
public class CacheAspect {

    private final ObjectProvider<RedisCacheService> cacheProvider;

    public CacheAspect(ObjectProvider<RedisCacheService> cacheProvider) {
        this.cacheProvider = cacheProvider;
    }

    /**
     * Intercepts methods annotated with {@code @Cacheable} and serves from
     * cache or computes via the cache-aside pattern.
     */
    @Around("@annotation(com.bhukkad.common.cache.Cacheable)")
    public Object cacheable(ProceedingJoinPoint pjp) throws Throwable {
        Cacheable ann = getAnnotation(pjp);
        if (ann == null) {
            return pjp.proceed();
        }

        String key = buildKey(pjp, ann);
        RedisCacheService cache = cacheProvider.getIfAvailable();
        if (cache == null) {
            return pjp.proceed();
        }

        try {
            Object result = cache.getOrCompute(key, Object.class, ann.ttl(), () -> {
                try {
                    return pjp.proceed();
                } catch (Throwable ex) {
                    throw new RuntimeException(ex);
                }
            });
            return result;
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause != null && cause instanceof Throwable) {
                throw (Throwable) cause;
            }
            throw e;
        } catch (Exception e) {
            // If cache computation fails, fall through to direct execution
            return pjp.proceed();
        }
    }

    private Cacheable getAnnotation(ProceedingJoinPoint pjp) {
        for (java.lang.annotation.Annotation a : pjp.getTarget().getClass().getAnnotations()) {
            if (a instanceof Cacheable) {
                return (Cacheable) a;
            }
        }
        // Check method-level annotation
        java.lang.reflect.Method method = getMethod(pjp);
        if (method != null) {
            Cacheable methodAnn = method.getAnnotation(Cacheable.class);
            if (methodAnn != null) {
                return methodAnn;
            }
        }
        return null;
    }

    private java.lang.reflect.Method getMethod(ProceedingJoinPoint pjp) {
        try {
            java.lang.reflect.Method[] methods = pjp.getTarget().getClass().getMethods();
            String name = pjp.getSignature().getName();
            for (java.lang.reflect.Method m : methods) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildKey(ProceedingJoinPoint pjp, Cacheable ann) {
        StringBuilder sb = new StringBuilder();
        if (ann.value() != null && !ann.value().isEmpty()) {
            sb.append(ann.value()).append(":");
        }
        sb.append(pjp.getTarget().getClass().getSimpleName()).append(".");
        sb.append(pjp.getSignature().getName()).append(":");
        if (pjp.getArgs() != null) {
            for (int i = 0; i < pjp.getArgs().length; i++) {
                if (i > 0) sb.append(":");
                sb.append(pjp.getArgs()[i] != null ? pjp.getArgs()[i].toString() : "null");
            }
        }
        return sb.toString();
    }
}
