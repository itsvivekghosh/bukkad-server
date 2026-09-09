package com.bhukkad.realtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.bhukkad.realtime", "com.bhukkad.common"})
@EnableScheduling
@EnableConfigurationProperties
@EntityScan(basePackages = {"com.bhukkad.realtime", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.realtime", "com.bhukkad.common"})
@EnableJpaAuditing
public class RealtimeServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(RealtimeServiceApplication.class, args);
	}
}
