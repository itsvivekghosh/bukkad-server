package com.bhukkad.engagement.chat.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Development implementation of OrderLookupTool.
 * <p>
 * <b>DEV ONLY</b> — Returns mock order data. In production, integrate with Order Service.
 */
@Component
@Profile({"dev", "test", "local"})
public class DevOrderLookupTool implements OrderLookupTool {

    private static final Random RANDOM = new Random();

    @Override
    public Optional<OrderDetails> lookupOrder(Long customerId, Long orderId) {
        // Simulate ownership check - in real impl, call OrderOwnershipClient
        if (customerId == null || orderId == null) {
            return Optional.empty();
        }

        // Mock data for known test orders
        if (orderId == 1001L) {
            return Optional.of(new OrderDetails(
                    1001L, "ORD-1001", "DELIVERED",
                    "Spice Garden",
                    List.of(
                            new OrderItem("Butter Chicken", 2, 12.99),
                            new OrderItem("Garlic Naan", 3, 3.99),
                            new OrderItem("Mango Lassi", 2, 4.50)
                    ),
                    47.94, "PAID", "2024-01-15T12:30:00"
            ));
        }
        if (orderId == 1002L) {
            return Optional.of(new OrderDetails(
                    1002L, "ORD-1002", "PREPARING",
                    "Bella Italia",
                    List.of(
                            new OrderItem("Margherita Pizza", 1, 15.99),
                            new OrderItem("Caesar Salad", 1, 8.99)
                    ),
                    24.98, "PAID", "2024-01-15T19:45:00"
            ));
        }

        // Generate deterministic mock data for other orders
        return Optional.of(new OrderDetails(
                orderId, "ORD-" + orderId, randomStatus(),
                "Restaurant " + (orderId % 50 + 1),
                List.of(
                        new OrderItem("Item A", 1 + RANDOM.nextInt(3), 10.0 + RANDOM.nextDouble() * 20),
                        new OrderItem("Item B", 1, 5.0 + RANDOM.nextDouble() * 10)
                ),
                20.0 + RANDOM.nextDouble() * 50, "PAID", "2024-01-15T12:00:00"
        ));
    }

    private String randomStatus() {
        String[] statuses = {"PLACED", "CONFIRMED", "PREPARING", "READY_FOR_PICKUP", "PICKED_UP", "DELIVERING", "DELIVERED"};
        return statuses[RANDOM.nextInt(statuses.length)];
    }
}