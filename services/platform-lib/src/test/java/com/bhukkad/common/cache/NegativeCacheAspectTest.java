package com.bhukkad.common.cache;

import java.util.Optional;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NegativeCacheAspect}: verifies an empty result is
 * cached as a negative marker and a second call within the TTL does not
 * re-invoke the supplier.
 */
class NegativeCacheAspectTest {

    @NegativeCacheable(cacheName = "restaurant", ttlSeconds = 5)
    public Optional<String> find(Long id) {
        return Optional.empty();
    }

    @Test
    void negativeResultCachedAndSecondCallSkipsSupplier() throws Throwable {
        RedisCacheService cache = mock(RedisCacheService.class);
        when(cache.exists(anyString())).thenReturn(false).thenReturn(true);

        ObjectProvider<RedisCacheService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(cache);
        NegativeCacheProperties properties = new NegativeCacheProperties();
        properties.setEnabled(true);
        properties.setTtlSeconds(5);

        NegativeCacheAspect aspect = new NegativeCacheAspect(provider, properties);

        ProceedingJoinPoint pjp = proceedJoinPoint(Optional.empty());

        // First call: cache miss → supplier invoked, negative marker stored.
        Object result = assertDoesNotThrow(() -> aspect.negativeCacheable(pjp));
        assertEquals(Optional.empty(), result);
        verify(pjp, times(1)).proceed();
        verify(cache).set(anyString(), eq(NegativeCacheMarker.INSTANCE), eq(5L));

        // Second call within the TTL: negative marker present → short-circuits,
        // supplier is NOT invoked again.
        Object result2 = assertDoesNotThrow(() -> aspect.negativeCacheable(pjp));
        assertEquals(Optional.empty(), result2);
        verify(pjp, times(1)).proceed();
    }

    @Test
    void nonEmptyResultIsNotCachedAsNegative() throws Throwable {
        RedisCacheService cache = mock(RedisCacheService.class);

        ObjectProvider<RedisCacheService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(cache);
        NegativeCacheProperties properties = new NegativeCacheProperties();
        properties.setEnabled(true);

        NegativeCacheAspect aspect = new NegativeCacheAspect(provider, properties);

        ProceedingJoinPoint pjp = proceedJoinPoint(Optional.of("found"));

        Object result = assertDoesNotThrow(() -> aspect.negativeCacheable(pjp));
        assertEquals(Optional.of("found"), result);
        verify(pjp, times(1)).proceed();
        verify(cache, never()).set(anyString(), any(), anyLong());
    }

    @Test
    void disabledNegativeCachePassesThrough() throws Throwable {
        RedisCacheService cache = mock(RedisCacheService.class);

        ObjectProvider<RedisCacheService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(cache);
        NegativeCacheProperties properties = new NegativeCacheProperties();
        properties.setEnabled(false);

        NegativeCacheAspect aspect = new NegativeCacheAspect(provider, properties);

        ProceedingJoinPoint pjp = proceedJoinPoint(Optional.empty());

        Object result = assertDoesNotThrow(() -> aspect.negativeCacheable(pjp));
        assertEquals(Optional.empty(), result);
        verify(pjp, times(1)).proceed();
        verify(cache, never()).exists(anyString());
        verify(cache, never()).set(anyString(), any(), anyLong());
    }

    private ProceedingJoinPoint proceedJoinPoint(Object proceedResult) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("find");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getTarget()).thenReturn(this);
        when(pjp.getArgs()).thenReturn(new Object[]{1L});
        when(pjp.proceed()).thenReturn(proceedResult);
        return pjp;
    }
}