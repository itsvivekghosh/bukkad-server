package com.bhukkad.delivery.live;

import com.bhukkad.delivery.dto.response.OrderLiveUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rider breadcrumbs: GEO write is best-effort, live broadcast must happen no
 * matter what Redis says; tracking tokens round-trip through the value store.
 */
@ExtendWith(MockitoExtension.class)
class RiderLocationTrackingServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private GeoOperations<String, String> geoOps;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private OrderLiveUpdateBroadcaster broadcaster;

    private RiderLocationTrackingService service;

    private RiderLocationTrackingService service() {
        if (service == null) {
            service = new RiderLocationTrackingService(redisTemplate, broadcaster);
        }
        return service;
    }

    @Test
    void publishRiderLocation_storesGeoAndBroadcasts() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOps);
        LocalDateTime etaAt = LocalDateTime.of(2026, 9, 5, 12, 30);

        service().publishRiderLocation(3L, 7L, 11L, 9L, "BK-7", 12.9, 77.6, 8, etaAt);

        verify(geoOps).add(eq("geo:rider:locations"), any(Point.class), eq("3"));
        verify(broadcaster).broadcastRiderLocation(7L, 11L, 9L, 3L, 12.9, 77.6, "BK-7", 8, etaAt);
    }

    @Test
    void publishRiderLocation_missingRequiredIdsIsSkipped() {
        service().publishRiderLocation(null, 7L, 11L, 9L, "BK-7", 12.9, 77.6, null, null);
        service().publishRiderLocation(3L, null, 11L, 9L, "BK-7", 12.9, 77.6, null, null);
        service().publishRiderLocation(3L, 7L, 11L, 9L, "BK-7", null, 77.6, null, null);
        service().publishRiderLocation(3L, 7L, 11L, 9L, "BK-7", 12.9, null, null, null);

        verifyNoInteractions(redisTemplate, broadcaster);
    }

    @Test
    void publishRiderLocation_geoFailureStillBroadcasts() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOps);
        doThrow(new RuntimeException("redis down"))
                .when(geoOps).add(anyString(), any(Point.class), anyString());

        service().publishRiderLocation(3L, 7L, null, null, null, 12.9, 77.6, null, null);

        verify(broadcaster).broadcastRiderLocation(7L, null, null, 3L, 12.9, 77.6, null, null, null);
    }

    @Test
    void getRiderLocation_returnsFirstPosition() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOps);
        Point point = new Point(77.6, 12.9);
        when(geoOps.position(eq("geo:rider:locations"), any(String[].class))).thenReturn(List.of(point));

        assertThat(service().getRiderLocation(3L)).isEqualTo(point);
    }

    @Test
    void getRiderLocation_nullAgentReturnsNullWithoutRedis() {
        assertThat(service().getRiderLocation(null)).isNull();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void getRiderLocation_emptyOrMissingPositionsReturnNull() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOps);
        when(geoOps.position(anyString(), any(String[].class))).thenReturn(null);
        assertThat(service().getRiderLocation(3L)).isNull();

        when(geoOps.position(anyString(), any(String[].class))).thenReturn(List.of());
        assertThat(service().getRiderLocation(3L)).isNull();

        when(geoOps.position(anyString(), any(String[].class)))
                .thenReturn(java.util.Arrays.asList((Point) null));
        assertThat(service().getRiderLocation(3L)).isNull();
    }

    @Test
    void getRiderLocation_redisFailureReturnsNull() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOps);
        when(geoOps.position(anyString(), any(String[].class)))
                .thenThrow(new RuntimeException("redis down"));

        assertThat(service().getRiderLocation(3L)).isNull();
    }

    @Test
    void createTrackingToken_persistsOrderMapping() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        String token = service().createTrackingToken(7L);

        assertThat(token).hasSize(32).matches("[0-9a-f]{32}");
        verify(valueOps).set(eq("tracking:token:" + token), eq("7"), eq(86_400_000L),
                eq(java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    @Test
    void createTrackingToken_redisFailureStillReturnsToken() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), anyLong(),
                        any(java.util.concurrent.TimeUnit.class));

        assertThat(service().createTrackingToken(7L)).hasSize(32);
    }

    @Test
    void isValidTrackingToken_matchesStoredOrder() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("tracking:token:abc")).thenReturn("7");

        assertThat(service().isValidTrackingToken(7L, "abc")).isTrue();
    }

    @Test
    void isValidTrackingToken_mismatchOrMissingIsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("tracking:token:abc")).thenReturn("8");
        assertThat(service().isValidTrackingToken(7L, "abc")).isFalse();

        when(valueOps.get("tracking:token:zzz")).thenReturn(null);
        assertThat(service().isValidTrackingToken(7L, "zzz")).isFalse();
    }

    @Test
    void isValidTrackingToken_blankInputReturnsFalseWithoutRedis() {
        assertThat(service().isValidTrackingToken(null, "abc")).isFalse();
        assertThat(service().isValidTrackingToken(7L, "  ")).isFalse();
        assertThat(service().isValidTrackingToken(7L, null)).isFalse();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void isValidTrackingToken_redisFailureReturnsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(service().isValidTrackingToken(7L, "abc")).isFalse();
    }
}
