package com.bhukkad.common.cache;

import java.util.List;
import java.util.function.Supplier;

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
 * Unit tests for {@link CacheWarmupService}: verifies warmup populates every
 * contributed key into L1+L2 and that a failing loader is logged and never
 * blocks startup.
 */
class CacheWarmupServiceTest {

    @Test
    void warmPopulatesAllKeysAndSurvivesFailingLoader() {
        RedisCacheService cache = mock(RedisCacheService.class);
        // Invoke the loader exactly as the cache-aside path would, so a
        // throwing loader propagates out of getOrCompute and is swallowed by
        // the warmup service instead of aborting startup.
        when(cache.getOrCompute(anyString(), eq(Object.class), anyLong(), any()))
                .thenAnswer(invocation -> {
                    Supplier<Object> loader = invocation.getArgument(3);
                    return loader.get();
                });

        CacheWarmupRegistry good = () -> List.of(
                new CacheWarmupEntry("warm:key:1", 60, () -> "value-1"),
                new CacheWarmupEntry("warm:key:2", 120, () -> "value-2"));
        CacheWarmupRegistry bad = () -> List.of(
                new CacheWarmupEntry("warm:key:bad", 30, () -> {
                    throw new RuntimeException("loader boom");
                }));

        CacheWarmupProperties properties = new CacheWarmupProperties();
        properties.setEnabled(true);

        ObjectProvider<RedisCacheService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(cache);
        CacheWarmupService service = new CacheWarmupService(
                List.of(good, bad), provider, properties);

        assertDoesNotThrow(service::warm,
                "warmup must not fail when a contributor loader throws");

        verify(cache, times(3)).getOrCompute(anyString(), eq(Object.class), anyLong(), any());
        verify(cache).getOrCompute(eq("warm:key:1"), eq(Object.class), eq(60L), any());
        verify(cache).getOrCompute(eq("warm:key:2"), eq(Object.class), eq(120L), any());
        verify(cache).getOrCompute(eq("warm:key:bad"), eq(Object.class), eq(30L), any());
    }

    @Test
    void warmDoesNothingWhenDisabled() {
        RedisCacheService cache = mock(RedisCacheService.class);
        CacheWarmupProperties properties = new CacheWarmupProperties();
        properties.setEnabled(false);

        ObjectProvider<RedisCacheService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(cache);
        CacheWarmupService service = new CacheWarmupService(List.of(), provider, properties);

        assertDoesNotThrow(service::warm);
        verify(cache, never()).getOrCompute(anyString(), any(), anyLong(), any());
    }
}