package com.bhukkad.referral;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.bhukkad.referral", "com.bhukkad.common"})
@ConfigurationPropertiesScan(basePackages = "com.bhukkad.referral")
@EntityScan(basePackages = {"com.bhukkad.referral", "com.bhukkad.common.outbox", "com.bhukkad.common.idempotency", "com.bhukkad.common.saga"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.referral", "com.bhukkad.common.outbox", "com.bhukkad.common.idempotency", "com.bhukkad.common.saga"})
public class ReferralServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReferralServiceApplication.class, args);
    }
}