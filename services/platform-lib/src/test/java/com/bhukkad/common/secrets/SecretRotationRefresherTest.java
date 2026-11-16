package com.bhukkad.common.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SecretRotationRefresher}: re-reads mounted secret
 * files and pushes changed values into the environment and bound properties.
 */
class SecretRotationRefresherTest {

    @TempDir
    Path tempDir;

    private ConfigurableEnvironment environmentWith(Map<String, Object> properties) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("yaml", properties));
        return environment;
    }

    @Test
    void refreshPicksUpRotatedSecretFiles() throws Exception {
        Path dbPassword = tempDir.resolve("db-password");
        Path jwtSecret = tempDir.resolve("jwt-secret");
        Files.writeString(dbPassword, "old-db-password", StandardCharsets.UTF_8);
        Files.writeString(jwtSecret, "old-jwt-secret", StandardCharsets.UTF_8);

        ConfigurableEnvironment env = environmentWith(Map.of(
                "spring.datasource.password", "${file:" + dbPassword + "}",
                "app.auth.jwt.secret", "${file:" + jwtSecret + ":${JWT_SECRET:fallback}}"));
        new SecretFileEnvironmentPostProcessor().postProcessEnvironment(env, null);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
             SecretRotationRefresher refresher = new SecretRotationRefresher(env, context, Duration.ofSeconds(60))) {

            context.refresh();

            // First pass: nothing changed yet.
            assertThat(refresher.refresh()).isEmpty();

            // Rotate both files.
            Files.writeString(dbPassword, "new-db-password", StandardCharsets.UTF_8);
            Files.writeString(jwtSecret, "new-jwt-secret", StandardCharsets.UTF_8);

            List<String> changed = refresher.refresh();

            assertThat(changed).containsExactlyInAnyOrder("spring.datasource.password", "app.auth.jwt.secret");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("new-db-password");
            assertThat(env.getProperty("app.auth.jwt.secret")).isEqualTo("new-jwt-secret");
        }
    }

    @Test
    void refreshRebindsConfigurationPropertiesBeans() throws Exception {
        Path dbPassword = tempDir.resolve("db-password");
        Files.writeString(dbPassword, "old-password", StandardCharsets.UTF_8);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            ConfigurableEnvironment contextEnv = context.getEnvironment();
            contextEnv.getPropertySources()
                    .addLast(new MapPropertySource("yaml",
                            Map.of("app.test.password", "${file:" + dbPassword + "}")));
            new SecretFileEnvironmentPostProcessor().postProcessEnvironment(contextEnv, null);
            context.register(TestConfig.class);
            context.refresh();

            TestProperties properties = context.getBean(TestProperties.class);
            assertThat(properties.getPassword()).isNull();

            try (SecretRotationRefresher refresher = new SecretRotationRefresher(contextEnv, context,
                    Duration.ofSeconds(60))) {
                Files.writeString(dbPassword, "rotated-password", StandardCharsets.UTF_8);
                refresher.refresh();
            }

            assertThat(properties.getPassword()).isEqualTo("rotated-password");
        }
    }

    @Test
    void refreshIsNoopWhenNothingChanged() throws Exception {
        Path dbPassword = tempDir.resolve("db-password");
        Files.writeString(dbPassword, "password", StandardCharsets.UTF_8);

        ConfigurableEnvironment env = environmentWith(Map.of(
                "spring.datasource.password", "${file:" + dbPassword + "}"));
        new SecretFileEnvironmentPostProcessor().postProcessEnvironment(env, null);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
             SecretRotationRefresher refresher = new SecretRotationRefresher(env, context, Duration.ofSeconds(60))) {
            context.refresh();
            assertThat(refresher.refresh()).isEmpty();
            assertThat(refresher.refresh()).isEmpty();
        }
    }

    @ConfigurationProperties(prefix = "app.test")
    static class TestProperties {
        private String password;

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    @Configuration
    static class TestConfig {
        @Bean
        @ConfigurationProperties(prefix = "app.test")
        TestProperties testProperties() {
            return new TestProperties();
    }
}

}
