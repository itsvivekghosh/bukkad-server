package com.bhukkad.social;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.bhukkad.social", "com.bhukkad.common"})
@EntityScan(basePackages = {"com.bhukkad.social", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.social", "com.bhukkad.common"})
@EnableJpaAuditing
public class SocialServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SocialServiceApplication.class, args);
    }
}
