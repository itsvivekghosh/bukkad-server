package com.bhukkad.restaurant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Restaurant service (P2). Owns the {@code bhukkad_restaurants} database:
 * restaurants, menu, cuisines and availability.
 *
 * <p>Scans {@code com.bhukkad.common} too so the platform entities/repositories
 * (outbox, idempotency, saga) and their beans are wired into this service.</p>
 */
@SpringBootApplication(scanBasePackages = {"com.bhukkad.restaurant", "com.bhukkad.common"})
@EntityScan(basePackages = {"com.bhukkad.restaurant", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.restaurant", "com.bhukkad.common"})
@EnableJpaAuditing
public class RestaurantServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RestaurantServiceApplication.class, args);
    }
}
