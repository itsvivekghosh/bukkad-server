package com.bhukkad.engagement.chat.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Random;

/**
 * Development implementation of OrderTrackingTool.
 * <p>
 * <b>DEV ONLY</b> — Returns mock tracking data. In production, integrate with Realtime Service.
 */
@Component
@Profile({"dev", "test", "local"})
public class DevOrderTrackingTool implements OrderTrackingTool {

    private static final Random RANDOM = new Random();

    @Override
    public Optional<TrackingInfo> trackOrder(Long customerId, Long orderId) {
        if (customerId == null || orderId == null) {
            return Optional.empty();
        }

        String[] statuses = {"PREPARING", "READY_FOR_PICKUP", "PICKED_UP", "DELIVERING", "DELIVERED"};
        String status = statuses[RANDOM.nextInt(statuses.length)];

        return Optional.of(new TrackingInfo(
                orderId,
                status,
                "Restaurant " + (orderId % 50 + 1),
                status.equals("DELIVERED") ? null : "Rider " + (orderId % 20 + 1),
                status.equals("DELIVERED") ? null : "+1-555-" + String.format("%04d", RANDOM.nextInt(10000)),
                status.equals("DELIVERED") ? null : 37.7749 + RANDOM.nextDouble() * 0.1,
                status.equals("DELIVERED") ? null : -122.4194 + RANDOM.nextDouble() * 0.1,
                status.equals("DELIVERED") ? "Delivered" : String.format("%d min", 5 + RANDOM.nextInt(30)),
                java.time.LocalDateTime.now().minusMinutes(RANDOM.nextInt(10)).toString()
        ));
    }
}