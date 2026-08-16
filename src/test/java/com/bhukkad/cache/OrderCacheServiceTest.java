package com.bhukkad.cache;

import com.bhukkad.dto.response.OrderResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCacheServiceTest {

    @Mock
    private RedisCacheService cacheService;

    @InjectMocks
    private OrderCacheService orderCacheService;

    @Test
    void getTrackedOrder_delegatesToRedis() {
        OrderResponse response = OrderResponse.builder().id(1L).build();
        when(cacheService.get(CacheKeyGenerator.orderTrack(1L, 7L), OrderResponse.class))
                .thenReturn(Optional.of(response));

        assertTrue(orderCacheService.getTrackedOrder(1L, 7L).isPresent());
        assertEquals(1L, orderCacheService.getTrackedOrder(1L, 7L).get().getId());
    }

    @Test
    void getTrackedOrder_missesWhenCustomerDiffers() {
        when(cacheService.get(CacheKeyGenerator.orderTrack(1L, 99L), OrderResponse.class))
                .thenReturn(Optional.empty());

        assertTrue(orderCacheService.getTrackedOrder(1L, 99L).isEmpty());
    }

    @Test
    void cacheTrackedOrder_usesConfiguredTtl() {
        ReflectionTestUtils.setField(orderCacheService, "orderTrackTtlSeconds", 45L);
        OrderResponse response = OrderResponse.builder().id(2L).build();

        orderCacheService.cacheTrackedOrder(2L, 7L, response);

        verify(cacheService).set(CacheKeyGenerator.orderTrack(2L, 7L), response, 45L);
    }

    @Test
    void invalidateOrder_deletesOrderTrackAndPatterns() {
        orderCacheService.invalidateOrder(5L, 1L, 10L);

        verify(cacheService).delete(CacheKeyGenerator.order(5L));
        verify(cacheService).deletePattern(CacheKeyGenerator.orderTrackPattern(5L));
        verify(cacheService).deletePattern(CacheKeyGenerator.orderPattern(1L));
        verify(cacheService).delete(CacheKeyGenerator.kitchenQueue(10L));
    }

    @Test
    void invalidateOrder_skipsNullCustomerAndRestaurant() {
        orderCacheService.invalidateOrder(5L, null, null);

        verify(cacheService).delete(CacheKeyGenerator.order(5L));
        verify(cacheService).deletePattern(CacheKeyGenerator.orderTrackPattern(5L));
    }
}
