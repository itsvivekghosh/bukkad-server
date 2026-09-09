package com.bhukkad.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.bhukkad.notification", "com.bhukkad.common"})
@EntityScan(basePackages = {"com.bhukkad.notification", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.notification", "com.bhukkad.common"})
@EnableJpaAuditing
public class NotificationServiceApplication {
    public static void main(String[] args) { SpringApplication.run(NotificationServiceApplication.class, args); }
}