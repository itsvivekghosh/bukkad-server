package com.bhukkad.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that Redis Lettuce connection pool settings are correctly configured
 * in the platform-lib {@code application.yml}. These settings are loaded by
 * Spring Boot and applied to the Lettuce connection pool (when pooling is enabled).
 *
 * <p>Per docs/architecture-audit.md §4.2, commerce/engagement (high throughput)
 * services use: max-active=64, max-idle=24, min-idle=8, max-wait=2000ms.</p>
 */
class RedisPoolConfigTest {

    @Test
    void lettucePoolSettingsAreLoadedFromApplicationYml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        factory.afterPropertiesSet();
        Properties props = factory.getObject();

        // Pool sizing for high-throughput services (commerce/engagement)
        assertThat(props.getProperty("spring.data.redis.lettuce.pool.max-active"))
                .as("max-active connections")
                .isEqualTo("64");
        assertThat(props.getProperty("spring.data.redis.lettuce.pool.max-idle"))
                .as("max-idle connections")
                .isEqualTo("24");
        assertThat(props.getProperty("spring.data.redis.lettuce.pool.min-idle"))
                .as("min-idle connections")
                .isEqualTo("8");
        assertThat(props.getProperty("spring.data.redis.lettuce.pool.max-wait"))
                .as("max-wait (ms)")
                .isEqualTo("2000");
        assertThat(props.getProperty("spring.data.redis.lettuce.pool.enabled"))
                .as("pool enabled")
                .isEqualTo("true");
    }
}