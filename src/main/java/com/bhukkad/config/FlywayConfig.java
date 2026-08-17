package com.bhukkad.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Repairs Flyway checksum/history after migration squash, then applies pending migrations.
 * Active only in staging and production where RDS already has historical schema versions.
 */
@Configuration
@Profile({"staging", "prod"})
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
