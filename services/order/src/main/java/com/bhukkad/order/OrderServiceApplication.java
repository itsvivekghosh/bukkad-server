package com.bhukkad.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Order service (P4). Owns {@code orders}: carts, orders, order items
 * and timeline. Order creation is a saga (reserve stock → charge payment →
 * confirm) with the outbox emitting {@code order.events.v1}.
 */
@SpringBootApplication(scanBasePackages = {"com.bhukkad.order", "com.bhukkad.common"})
@EntityScan(basePackages = {"com.bhukkad.order", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.order", "com.bhukkad.common"})
@EnableJpaAuditing
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
