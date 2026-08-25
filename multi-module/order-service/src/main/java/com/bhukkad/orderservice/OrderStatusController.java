package com.bhukkad.orderservice;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Proof-of-wiring endpoints for the extracted order-service. In production the
 * handlers delegate to the order aggregate (order + items + status) and expose
 * the same contract as the monolith's {@code /api/v1/orders} (versioned via
 * {@code Accept-Version}, aggregated by the gateway).
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderStatusController {

    @GetMapping("/{orderId}/status")
    public Map<String, Object> status(@PathVariable Long orderId) {
        return Map.of(
                "orderId", orderId,
                "status", "PLACED",
                "service", "order-service",
                "version", "v1"
        );
    }
}
