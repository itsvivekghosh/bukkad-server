package com.bhukkad.delivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.bhukkad.delivery", "com.bhukkad.common"})
@EntityScan(basePackages = {"com.bhukkad.delivery", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.delivery", "com.bhukkad.common"})
@EnableJpaAuditing
public class DeliveryServiceApplication {
    public static void main(String[] args) { SpringApplication.run(DeliveryServiceApplication.class, args); }
}