package com.bhukkad.cache;

import com.bhukkad.dto.response.OrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderCacheService {

    private final RedisCacheService cacheService;

    @Value("${cache.ttl.order-track:30}")
    private long orderTrackTtlSeconds;

    public Optional<OrderResponse> getTrackedOrder(Long orderId) {
        return cacheService.get(CacheKeyGenerator.orderTrack(orderId), OrderResponse.class);
    }

    public void cacheTrackedOrder(Long orderId, OrderResponse response) {
        cacheService.set(CacheKeyGenerator.orderTrack(orderId), response, orderTrackTtlSeconds);
    }

    public void invalidateOrder(Long orderId, Long customerId, Long restaurantId) {
        cacheService.delete(CacheKeyGenerator.order(orderId));
        cacheService.delete(CacheKeyGenerator.orderTrack(orderId));
        if (customerId != null) {
            cacheService.deletePattern(CacheKeyGenerator.orderPattern(customerId));
        }
        if (restaurantId != null) {
            cacheService.delete(CacheKeyGenerator.kitchenQueue(restaurantId));
        }
    }
}
