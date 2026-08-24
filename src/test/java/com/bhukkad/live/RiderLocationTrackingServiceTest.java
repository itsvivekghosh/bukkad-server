package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiderLocationTrackingServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private GeoOperations<String, String> geoOperations;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private OrderSseStreamService sseStreamService;

    private RiderLocationTrackingService service;

    @BeforeEach
    void setUp() {
        service = new RiderLocationTrackingService(stringRedisTemplate, sseStreamService);
        // doReturn form: invoking opsForGeo()/opsForValue() on the mock would
        // return null (they are concrete methods), so stub by return value.
        lenient().doReturn(geoOperations).when(stringRedisTemplate).opsForGeo();
        lenient().doReturn(valueOperations).when(stringRedisTemplate).opsForValue();
    }

    @Test
    void publishRiderLocation_storesGeoFixAndBroadcastsToCustomer() {
        service.publishRiderLocation(7L, 100L, 1L, 3L, "ORD-100",
                12.97, 77.59, 18, LocalDateTime.now().plusMinutes(18));

        verify(geoOperations).add(eq("geo:rider:locations"),
                any(Point.class), eq("7"));
        verify(sseStreamService).broadcastCustomer(eq(100L), any(OrderLiveUpdate.class));
    }

    @Test
    void publishRiderLocation_skipsWhenOrderOrAgentMissing() {
        service.publishRiderLocation(null, 100L, 1L, 3L, "ORD-100", 12.97, 77.59, 18, null);
        service.publishRiderLocation(7L, null, 1L, 3L, "ORD-100", 12.97, 77.59, 18, null);

        verify(geoOperations, org.mockito.Mockito.never())
                .add(anyString(), any(Point.class), anyString());
    }

    @Test
    void getRiderLocation_returnsNullWhenNoFix() {
        when(geoOperations.position("geo:rider:locations", "7"))
                .thenReturn(java.util.Arrays.asList((Point) null));

        assertNull(service.getRiderLocation(7L));
    }

    @Test
    void getRiderLocation_returnsStoredPoint() {
        Point point = new Point(77.59, 12.97);
        when(geoOperations.position("geo:rider:locations", "7"))
                .thenReturn(List.of(point));

        Point result = service.getRiderLocation(7L);

        assertNotNull(result);
        assertEquals(point, result);
    }

    @Test
    void createTrackingToken_storesTokenBoundToOrder() {
        String token = service.createTrackingToken(42L);

        assertNotNull(token);
        verify(valueOperations).set(eq("tracking:token:" + token), eq("42"),
                eq(TimeUnit.HOURS.toMillis(24)), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void publishRiderLocation_redisGeoFailure_stillBroadcasts() {
        when(geoOperations.add(eq("geo:rider:locations"), any(Point.class), eq("7")))
                .thenThrow(new RuntimeException("redis down"));

        // Must not throw; the SSE broadcast still fires so the customer keeps
        // seeing location updates even if the GEO store is unavailable.
        service.publishRiderLocation(7L, 100L, 1L, 3L, "ORD-100",
                12.97, 77.59, 18, LocalDateTime.now());

        verify(sseStreamService).broadcastCustomer(eq(100L), any(OrderLiveUpdate.class));
    }

    @Test
    void getRiderLocation_returnsNullOnRedisFailure() {
        when(geoOperations.position(eq("geo:rider:locations"), eq("7")))
                .thenThrow(new RuntimeException("redis down"));

        assertNull(service.getRiderLocation(7L));
    }

    @Test
    void createTrackingToken_redisFailure_stillReturnsToken() {
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOperations)
                .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        // Fail open: a token is still handed out (it will simply not validate
        // if Redis stays down, but the caller experience is preserved).
        assertNotNull(service.createTrackingToken(42L));
    }

    @Test
    void isValidTrackingToken_returnsFalseOnRedisFailure() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));

        // Fail closed: unknown/unverifiable tokens must never grant access.
        assertFalse(service.isValidTrackingToken(42L, "abc"));
    }

    @Test
    void isValidTrackingToken_returnsTrueForMatchingOrder() {
        when(valueOperations.get("tracking:token:abc")).thenReturn("42");

        assertTrue(service.isValidTrackingToken(42L, "abc"));
    }

    @Test
    void isValidTrackingToken_rejectsMismatchAndBlank() {
        when(valueOperations.get("tracking:token:abc")).thenReturn("99");

        assertFalse(service.isValidTrackingToken(42L, "abc"));
        assertFalse(service.isValidTrackingToken(42L, ""));
        assertFalse(service.isValidTrackingToken(null, "abc"));
    }
}
