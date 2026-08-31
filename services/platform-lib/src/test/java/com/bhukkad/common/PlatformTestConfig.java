package com.bhukkad.common;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal Spring Boot configuration for the {@code @DataJpaTest} slice tests
 * in the common module. Because {@code platform-lib} is a library with no
 * {@code @SpringBootApplication} class, the slice bootstrapper needs a
 * {@code @SpringBootConfiguration} to find; this class provides it.
 *
 * <p>{@code @EnableJpaAuditing} mirrors the monolith's application class: the
 * platform entities ({@code OutboxEvent}, {@code IdempotencyRecord}) rely on
 * {@code @CreatedDate} to populate {@code created_at}.</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackages = "com.bhukkad.common")
@EnableJpaRepositories(basePackages = "com.bhukkad.common")
@EnableJpaAuditing
public class PlatformTestConfig {
}