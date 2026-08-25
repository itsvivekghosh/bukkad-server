package com.bhukkad.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the extracted order-service (Phase 3 proof).
 *
 * <p>Owns the ORDER domain: order, cart, group-order, subscription, gift. Its
 * Flyway migrations target the {@code bhukkad_orders} schema (V54); until the
 * split is complete it can run read-only against the monolith's
 * {@code bhukkad} schema via the {@code monolith} profile.</p>
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
