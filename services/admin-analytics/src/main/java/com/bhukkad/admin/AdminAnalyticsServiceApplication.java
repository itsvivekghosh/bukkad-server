package com.bhukkad.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.bhukkad.admin", "com.bhukkad.common"})
@EntityScan(basePackages = {"com.bhukkad.admin", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.admin", "com.bhukkad.common"})
@EnableJpaAuditing
public class AdminAnalyticsServiceApplication {
    public static void main(String[] args) { SpringApplication.run(AdminAnalyticsServiceApplication.class, args); }
}