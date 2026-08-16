package com.bhukkad.cache;

import com.bhukkad.dto.response.ServiceabilityResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ServiceabilityCacheService}.
 *
 * <p>{@link RedisCacheService} is mocked so the assertions cover the caching
 * contract: the composed key, the response type, the configured TTL and the
 * caller's loader reach Redis unchanged, and distinct request parameters produce
 * distinct keys (otherwise one customer's verdict could be served to another
 * location or cart value).
 *
 * <p>The {@code @Value} TTL field is set with
 * {@link ReflectionTestUtils#setField} because no Spring context is started.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class ServiceabilityCacheServiceTest {

    @Mock
    private RedisCacheService cacheService;

    @InjectMocks
    private ServiceabilityCacheService serviceabilityCacheService;

    @Test
    void getServiceability_usesComposedKeyTypeAndTtl() {
        ReflectionTestUtils.setField(serviceabilityCacheService, "serviceabilityTtlSeconds", 60L);
        ServiceabilityResponse response = ServiceabilityResponse.builder()
                .serviceable(true)
                .zoneId(9L)
                .estimatedDeliveryFee(29.0)
                .build();
        Supplier<ServiceabilityResponse> loader = () -> response;
        String expectedKey = "serviceability:restaurant:4:12.9:77.6:250.0";
        when(cacheService.getOrCompute(
                eq(expectedKey), eq(ServiceabilityResponse.class), eq(60L), any()))
                .thenReturn(response);

        ServiceabilityResponse result =
                serviceabilityCacheService.getServiceability(4L, 12.9, 77.6, 250.0, loader);

        assertSame(response, result);
        assertTrue(result.isServiceable());
        verify(cacheService).getOrCompute(expectedKey, ServiceabilityResponse.class, 60L, loader);
    }

    @Test
    void getServiceability_honoursConfiguredTtl() {
        ReflectionTestUtils.setField(serviceabilityCacheService, "serviceabilityTtlSeconds", 15L);
        ServiceabilityResponse response = ServiceabilityResponse.builder().serviceable(false).build();
        when(cacheService.getOrCompute(
                eq("serviceability:restaurant:1:0.0:0.0:0.0"),
                eq(ServiceabilityResponse.class),
                eq(15L),
                any()))
                .thenReturn(response);

        assertSame(response, serviceabilityCacheService.getServiceability(1L, 0.0, 0.0, 0.0, () -> response));
    }

    @Test
    void getServiceability_doesNotInvokeLoaderItself() {
        ReflectionTestUtils.setField(serviceabilityCacheService, "serviceabilityTtlSeconds", 60L);
        when(cacheService.getOrCompute(
                eq("serviceability:restaurant:4:12.9:77.6:0.0"),
                eq(ServiceabilityResponse.class),
                eq(60L),
                any()))
                .thenReturn(ServiceabilityResponse.builder().serviceable(true).build());

        // The loader must be passed through untouched; only a cache miss inside
        // RedisCacheService may run it (which would hit the database).
        serviceabilityCacheService.getServiceability(4L, 12.9, 77.6, 0.0, () -> {
            throw new AssertionError("loader must not be invoked by the cache service");
        });
    }

    @Test
    void keysDifferPerRestaurantLocationAndSubtotal() {
        assertNotEquals(
                CacheKeyGenerator.serviceability(4L, 12.9, 77.6, 250.0),
                CacheKeyGenerator.serviceability(5L, 12.9, 77.6, 250.0));
        assertNotEquals(
                CacheKeyGenerator.serviceability(4L, 12.9, 77.6, 250.0),
                CacheKeyGenerator.serviceability(4L, 12.91, 77.6, 250.0));
        assertNotEquals(
                CacheKeyGenerator.serviceability(4L, 12.9, 77.6, 250.0),
                CacheKeyGenerator.serviceability(4L, 12.9, 77.61, 250.0));
        assertNotEquals(
                CacheKeyGenerator.serviceability(4L, 12.9, 77.6, 250.0),
                CacheKeyGenerator.serviceability(4L, 12.9, 77.6, 500.0));
    }

    @Test
    void invalidateRestaurant_deletesByRestaurantPattern() {
        serviceabilityCacheService.invalidateRestaurant(4L);

        verify(cacheService).deletePattern("serviceability:restaurant:4");
        verify(cacheService, never()).delete(any());
    }
}
