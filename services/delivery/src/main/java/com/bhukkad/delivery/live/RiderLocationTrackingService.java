package com.bhukkad.delivery.live;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiderLocationTrackingService {

    private static final String GEO_KEY = "geo:rider:locations";
    private static final String TOKEN_PREFIX = "tracking:token:";
    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    private final StringRedisTemplate stringRedisTemplate;
    private final OrderLiveUpdateBroadcaster orderLiveUpdateBroadcaster;

    public void publishRiderLocation(Long agentId, Long orderId, Long customerId, Long restaurantId,
                                      String orderNumber, Double latitude, Double longitude,
                                      Integer liveEtaMinutes, LocalDateTime liveEtaAt) {
        if (agentId == null || orderId == null || latitude == null || longitude == null) {
            log.debug("Rider location publish skipped | agentId={} | orderId={}", agentId, orderId);
            return;
        }
        try {
            stringRedisTemplate.opsForGeo().add(GEO_KEY, new Point(longitude, latitude), String.valueOf(agentId));
        } catch (Exception ex) {
            log.warn("Redis GEO store failed | agentId={} | error={}", agentId, ex.getMessage());
        }

        orderLiveUpdateBroadcaster.broadcastRiderLocation(
                orderId, customerId, restaurantId, agentId, latitude, longitude,
                orderNumber, liveEtaMinutes, liveEtaAt);
    }

    public Point getRiderLocation(Long agentId) {
        if (agentId == null) {
            return null;
        }
        try {
            var positions = stringRedisTemplate.opsForGeo()
                    .position(GEO_KEY, String.valueOf(agentId));
            if (positions == null || positions.isEmpty() || positions.get(0) == null) {
                return null;
            }
            return positions.get(0);
        } catch (Exception ex) {
            log.warn("Redis GEO read failed | agentId={} | error={}", agentId, ex.getMessage());
            return null;
        }
    }

    public String createTrackingToken(Long orderId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            stringRedisTemplate.opsForValue().set(TOKEN_PREFIX + token, String.valueOf(orderId),
                    TOKEN_TTL.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            log.warn("Tracking token store failed | orderId={} | error={}", orderId, ex.getMessage());
        }
        return token;
    }

    public boolean isValidTrackingToken(Long orderId, String token) {
        if (orderId == null || !StringUtils.hasText(token)) {
            return false;
        }
        try {
            String stored = stringRedisTemplate.opsForValue().get(TOKEN_PREFIX + token);
            return stored != null && stored.equals(String.valueOf(orderId));
        } catch (Exception ex) {
            log.warn("Tracking token lookup failed | error={}", ex.getMessage());
            return false;
        }
    }
}
