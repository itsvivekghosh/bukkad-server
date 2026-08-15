package com.bhukkad.geo;

import com.bhukkad.config.GeoIndexProperties;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Restaurant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RestaurantGeoIndexService {

    private final StringRedisTemplate stringRedisTemplate;
    private final GeoIndexProperties geoIndexProperties;

    public boolean isEnabled() {
        return geoIndexProperties.isRedisGeoEnabled();
    }

    public void indexRestaurant(Restaurant restaurant) {
        if (!isEnabled() || restaurant == null || restaurant.getId() == null) {
            return;
        }
        Address address = restaurant.getAddress();
        if (address == null || address.getLatitude() == null || address.getLongitude() == null) {
            return;
        }
        try {
            Point point = new Point(address.getLongitude(), address.getLatitude());
            stringRedisTemplate.opsForGeo().add(
                    geoIndexProperties.getRestaurantsGeoKey(),
                    point,
                    restaurant.getId().toString());
        } catch (Exception ex) {
            log.warn("GEO_INDEX_FAILED | restaurantId={} | error={}", restaurant.getId(), ex.getMessage());
        }
    }

    public void removeRestaurant(Long restaurantId) {
        if (!isEnabled() || restaurantId == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForGeo().remove(geoIndexProperties.getRestaurantsGeoKey(),
                    restaurantId.toString());
        } catch (Exception ex) {
            log.warn("GEO_REMOVE_FAILED | restaurantId={} | error={}", restaurantId, ex.getMessage());
        }
    }

    public List<Long> findNearbyRestaurantIds(double latitude, double longitude, double radiusKm, int limit) {
        if (!isEnabled()) {
            return List.of();
        }
        try {
            Point center = new Point(longitude, latitude);
            Distance distance = new Distance(radiusKm, Metrics.KILOMETERS);
            Circle circle = new Circle(center, distance);
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                    .radius(geoIndexProperties.getRestaurantsGeoKey(), circle,
                            RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                    .sortAscending()
                                    .limit(limit));
            if (results == null) {
                return List.of();
            }
            List<Long> ids = new ArrayList<>();
            results.forEach(result -> {
                try {
                    ids.add(Long.parseLong(result.getContent().getName()));
                } catch (NumberFormatException ignored) {
                    // skip invalid member
                }
            });
            return ids;
        } catch (Exception ex) {
            log.warn("GEO_SEARCH_FAILED | error={}", ex.getMessage());
            return List.of();
        }
    }
}
