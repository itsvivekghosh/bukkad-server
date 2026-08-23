package com.bhukkad.geo;

import com.bhukkad.config.GeoIndexProperties;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Restaurant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestaurantGeoIndexServiceTest {

    private static final String GEO_KEY = "restaurants:geo";

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private GeoOperations<String, String> geoOperations;

    private final GeoIndexProperties properties = new GeoIndexProperties();

    private RestaurantGeoIndexService service;

    @BeforeEach
    void setUp() {
        properties.setRedisGeoEnabled(true);
        properties.setRestaurantsGeoKey(GEO_KEY);
        service = new RestaurantGeoIndexService(stringRedisTemplate, properties);
    }

    private Address address(Double latitude, Double longitude) {
        Address address = new Address();
        address.setLatitude(latitude);
        address.setLongitude(longitude);
        return address;
    }

    private Restaurant restaurant(Long id, Address address) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(id);
        restaurant.setAddress(address);
        return restaurant;
    }

    private GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults(String... names) {
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> results = java.util.Arrays.stream(names)
                .map(name -> new GeoResult<>(
                        new RedisGeoCommands.GeoLocation<>(name, new Point(1.0, 2.0)),
                        new Distance(0.5, Metrics.KILOMETERS)))
                .toList();
        return new GeoResults<>(results);
    }

    @Test
    void isEnabled_returnsFlag() {
        properties.setRedisGeoEnabled(true);
        assertTrue(service.isEnabled());

        properties.setRedisGeoEnabled(false);
        assertFalse(service.isEnabled());
    }

    @Test
    void indexRestaurant_success_addsPointWithLongitudeFirst() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        service.indexRestaurant(restaurant(123L, address(19.0760, 72.8777)));

        ArgumentCaptor<Point> pointCaptor = ArgumentCaptor.forClass(Point.class);
        verify(geoOperations).add(eq(GEO_KEY), pointCaptor.capture(), eq("123"));
        assertEquals(72.8777, pointCaptor.getValue().getX());
        assertEquals(19.0760, pointCaptor.getValue().getY());
    }

    @Test
    void indexRestaurant_disabled_isNoOp() {
        properties.setRedisGeoEnabled(false);

        service.indexRestaurant(restaurant(123L, address(19.0760, 72.8777)));

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void indexRestaurant_nullRestaurant_isNoOp() {
        service.indexRestaurant(null);

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void indexRestaurant_nullId_isNoOp() {
        service.indexRestaurant(restaurant(null, address(19.0760, 72.8777)));

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void indexRestaurant_nullAddress_isNoOp() {
        service.indexRestaurant(restaurant(123L, null));

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void indexRestaurant_nullLatitude_isNoOp() {
        service.indexRestaurant(restaurant(123L, address(null, 72.8777)));

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void indexRestaurant_nullLongitude_isNoOp() {
        service.indexRestaurant(restaurant(123L, address(19.0760, null)));

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void indexRestaurant_redisFailure_swallowsAndLogs() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.add(anyString(), any(Point.class), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        service.indexRestaurant(restaurant(123L, address(19.0760, 72.8777)));
    }

    @Test
    void removeRestaurant_success() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);

        service.removeRestaurant(123L);

        verify(geoOperations).remove(GEO_KEY, "123");
    }

    @Test
    void removeRestaurant_disabled_isNoOp() {
        properties.setRedisGeoEnabled(false);

        service.removeRestaurant(123L);

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void removeRestaurant_nullId_isNoOp() {
        service.removeRestaurant(null);

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void removeRestaurant_redisFailure_swallowsAndLogs() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.remove(anyString(), any(String[].class)))
                .thenThrow(new RuntimeException("redis down"));

        service.removeRestaurant(123L);
    }

    @Test
    void findNearbyRestaurantIds_disabled_returnsEmpty() {
        properties.setRedisGeoEnabled(false);

        List<Long> ids = service.findNearbyRestaurantIds(19.0, 72.0, 5.0, 10);

        assertTrue(ids.isEmpty());
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void findNearbyRestaurantIds_success_parsesIds() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(GEO_KEY), any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults("123", "456", "789"));

        List<Long> ids = service.findNearbyRestaurantIds(19.0, 72.0, 5.0, 10);

        assertEquals(List.of(123L, 456L, 789L), ids);
        ArgumentCaptor<Circle> circleCaptor = ArgumentCaptor.forClass(Circle.class);
        verify(geoOperations).radius(eq(GEO_KEY), circleCaptor.capture(),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class));
        Point center = circleCaptor.getValue().getCenter();
        assertEquals(72.0, center.getX());
        assertEquals(19.0, center.getY());
        assertEquals(5.0, circleCaptor.getValue().getRadius().getValue());
        assertEquals(Metrics.KILOMETERS, circleCaptor.getValue().getRadius().getMetric());
    }

    @Test
    void findNearbyRestaurantIds_skipsNonNumericMembers() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(GEO_KEY), any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults("123", "abc", "456", ""));

        List<Long> ids = service.findNearbyRestaurantIds(19.0, 72.0, 5.0, 10);

        assertEquals(List.of(123L, 456L), ids);
    }

    @Test
    void findNearbyRestaurantIds_nullResults_returnsEmpty() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(GEO_KEY), any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(null);

        List<Long> ids = service.findNearbyRestaurantIds(19.0, 72.0, 5.0, 10);

        assertTrue(ids.isEmpty());
    }

    @Test
    void findNearbyRestaurantIds_redisFailure_returnsEmpty() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(GEO_KEY), any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenThrow(new RuntimeException("timeout"));

        List<Long> ids = service.findNearbyRestaurantIds(19.0, 72.0, 5.0, 10);

        assertTrue(ids.isEmpty());
    }

    @Test
    void findNearbyRestaurantIds_emptyResults_returnsEmpty() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq(GEO_KEY), any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults());

        List<Long> ids = service.findNearbyRestaurantIds(19.0, 72.0, 5.0, 10);

        assertTrue(ids.isEmpty());
    }
}
