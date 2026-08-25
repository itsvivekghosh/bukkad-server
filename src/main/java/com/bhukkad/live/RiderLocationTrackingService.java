package com.bhukkad.live;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Publishes rider GPS positions so customers can track their order live, and
 * issues short-lived anonymous tracking tokens.
 *
 * <p>Positions are stored in a Redis GEO set (keyed by rider) so any
 * application instance can read the latest fix, and each update is pushed
 * through the Redis live-update relay ({@link OrderLiveUpdateBroadcaster}) so
 * every replica delivers the {@code RIDER_LOCATION} event to its connected SSE
 * clients — a rider pinging one instance is visible to customers connected to
 * any other instance. Tracking tokens are random, TTL-bound, and validated
 * against the order id so a guest without an account can follow delivery
 * without any auth header.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderLocationTrackingService {

    private static final String GEO_KEY = "geo:rider:locations";
    private static final String TOKEN_PREFIX = "tracking:token:";
    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    private final StringRedisTemplate stringRedisTemplate;
    private final OrderLiveUpdateBroadcaster orderLiveUpdateBroadcaster;

    /**
     * Records a rider GPS fix and broadcasts it to the order's customer stream.
     *
     * @param agentId       the rider
     * @param orderId       the order being delivered
     * @param customerId    the customer to notify
     * @param restaurantId  the restaurant for the live-update payload
     * @param orderNumber   human-readable order number
     * @param latitude      GPS latitude
     * @param longitude     GPS longitude
     * @param liveEtaMinutes refreshed ETA (may be null if unknown)
     * @param liveEtaAt     refreshed ETA timestamp (may be null)
     */
    public void publishRiderLocation(Long agentId, Long orderId, Long customerId, Long restaurantId,
                                     String orderNumber, Double latitude, Double longitude,
                                     Integer liveEtaMinutes, LocalDateTime liveEtaAt) {
        if (agentId == null || orderId == null || latitude == null || longitude == null) {
            log.debug("Rider location publish skipped | agentId={} | orderId={}", agentId, orderId);
            return;
        }
        try {
            // Upsert the latest fix in the GEO index (member = rider id).
            stringRedisTemplate.opsForGeo().add(GEO_KEY, new Point(longitude, latitude), String.valueOf(agentId));
        } catch (Exception ex) {
            log.warn("Redis GEO store failed | agentId={} | error={}", agentId, ex.getMessage());
        }

        // Publish through the Redis relay (cluster-safe: reaches SSE clients on
        // every replica), carrying the live ETA and order number for the map UI.
        orderLiveUpdateBroadcaster.broadcastRiderLocation(
                orderId, customerId, restaurantId, agentId, latitude, longitude,
                orderNumber, liveEtaMinutes, liveEtaAt);
    }

    /**
     * Returns the last known position of a rider, or {@code null} if none.
     */
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

    /**
     * Issues a random anonymous tracking token bound to an order.
     *
     * @param orderId the order to track
     * @return the tracking token (URL-safe UUID)
     */
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

    /**
     * Validates that a tracking token belongs to the given order.
     */
    public boolean isValidTrackingToken(Long orderId, String token) {
        if (orderId == null || !StringUtils.hasText(token)) {
            return false;
        }
        try {
            String stored = stringRedisTemplate.opsForValue().get(TOKEN_PREFIX + token);
            return stored != null && stored.equals(String.valueOf(orderId));
        } catch (Exception ex) {
            // Fail closed for tracking: an unknown token must never grant access.
            log.warn("Tracking token lookup failed | error={}", ex.getMessage());
            return false;
        }
    }
}
