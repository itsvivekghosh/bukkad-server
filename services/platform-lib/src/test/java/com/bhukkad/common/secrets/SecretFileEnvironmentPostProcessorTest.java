package com.bhukkad.common.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SecretFileEnvironmentPostProcessor}: expands
 * {@code ${file:...}} placeholders in configuration values before the context
 * is created.
 */
class SecretFileEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    private ConfigurableEnvironment environmentWith(Map<String, Object> properties) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("yaml", properties));
        return environment;
    }

    @Test
    void expandsFilePlaceholderIntoResolvedValue() throws Exception {
        Path file = tempDir.resolve("db-password");
        Files.writeString(file, "super-secret-db-password", StandardCharsets.UTF_8);

        ConfigurableEnvironment env = environmentWith(Map.of(
                "spring.datasource.password", "${file:" + file + ":${DB_PASSWORD:default}}"));

        new SecretFileEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertThat(env.getProperty("spring.datasource.password"))
                .isEqualTo("super-secret-db-password");
    }

    @Test
    void usesFallbackExpressionWhenFileIsMissing() {
        ConfigurableEnvironment env = environmentWith(Map.of(
                "app.prop", "${file:/missing/secrets/x:${MY_SECRET:env-value}}",
                "MY_SECRET", "env-value"));

        new SecretFileEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertThat(env.getProperty("app.prop")).isEqualTo("env-value");
    }

    @Test
    void leavesValuesWithoutFilePlaceholderUntouched() {
        ConfigurableEnvironment env = environmentWith(Map.of(
                "spring.datasource.password", "plain-password"));

        new SecretFileEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("plain-password");
    }

    @Test
    void commandLineArgumentsWinOverResolvedSecret() throws Exception {
        Path file = tempDir.resolve("db-password");
        Files.writeString(file, "file-secret", StandardCharsets.UTF_8);

        ConfigurableEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.addFirst(new MapPropertySource("commandLineArgs",
                Map.of("spring.datasource.password", "cli-value")));
        sources.addLast(new MapPropertySource("yaml",
                Map.of("spring.datasource.password", "${file:" + file + "}")));

        new SecretFileEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("cli-value");
    }

    @Test
    void doesNothingWhenNoPropertyReferencesASecretFile() {
        ConfigurableEnvironment env = environmentWith(Map.of(
                "spring.datasource.password", "plain",
                "other.prop", "value"));

        new SecretFileEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains(SecretFileEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isFalse();
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("plain");
    }
}
