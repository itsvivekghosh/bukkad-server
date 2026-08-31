package com.bhukkad.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Payment service (P5). Owns {@code bhukkad_payments}: payments, wallet
 * balances and transactions — the single writer for the money path. Idempotency
 * records prevent double-credit/double-refund (plan §13).
 */
@SpringBootApplication(scanBasePackages = {"com.bhukkad.payment", "com.bhukkad.common"})
@EntityScan(basePackages = {"com.bhukkad.payment", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.payment", "com.bhukkad.common"})
@EnableJpaAuditing
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
