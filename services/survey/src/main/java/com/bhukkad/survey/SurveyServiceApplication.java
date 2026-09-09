package com.bhukkad.survey;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.bhukkad.survey", "com.bhukkad.common"})
@EntityScan(basePackages = {"com.bhukkad.survey", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.survey", "com.bhukkad.common"})
public class SurveyServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurveyServiceApplication.class, args);
    }
}