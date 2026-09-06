package com.bhukkad.personalization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.bhukkad.personalization", "com.bhukkad.common"})
@EnableConfigurationProperties
@EntityScan(basePackages = {"com.bhukkad.personalization", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.personalization", "com.bhukkad.common"})
@EnableJpaAuditing
public class PersonalizationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PersonalizationServiceApplication.class, args);
	}
}
