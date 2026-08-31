package com.bhukkad.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Identity service (P3). Owns {@code bhukkad_identity}: customers, addresses;
 * issues JWTs (plan §8 — identity is the authN source of truth).
 */
@SpringBootApplication(scanBasePackages = {"com.bhukkad.identity", "com.bhukkad.common"})
@EntityScan(basePackages = {"com.bhukkad.identity", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.identity", "com.bhukkad.common"})
@EnableJpaAuditing
@ConfigurationPropertiesScan(basePackages = "com.bhukkad.identity")
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
