package com.bhukkad.growth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.bhukkad.growth", "com.bhukkad.common"})
@EnableConfigurationProperties
@EntityScan(basePackages = {"com.bhukkad.growth", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.growth", "com.bhukkad.common"})
@EnableJpaAuditing
public class GrowthServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(GrowthServiceApplication.class, args);
	}
}
