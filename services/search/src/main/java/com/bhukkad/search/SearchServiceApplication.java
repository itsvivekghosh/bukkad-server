package com.bhukkad.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.bhukkad.search", "com.bhukkad.common"})
@EntityScan(basePackages = {"com.bhukkad.search", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.search", "com.bhukkad.common"})
@EnableJpaAuditing
public class SearchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }
}
