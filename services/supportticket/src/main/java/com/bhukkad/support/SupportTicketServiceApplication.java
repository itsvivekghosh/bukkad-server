package com.bhukkad.support;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.bhukkad.support", "com.bhukkad.common"})
@ConfigurationPropertiesScan(basePackages = "com.bhukkad.support")
@EnableJpaAuditing
@EntityScan(basePackages = {"com.bhukkad.support", "com.bhukkad.common.outbox", "com.bhukkad.common.idempotency", "com.bhukkad.common.saga"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.support", "com.bhukkad.common.outbox", "com.bhukkad.common.idempotency", "com.bhukkad.common.saga"})
public class SupportTicketServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupportTicketServiceApplication.class, args);
    }
}