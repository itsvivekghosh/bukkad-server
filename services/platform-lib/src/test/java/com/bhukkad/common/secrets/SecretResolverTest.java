package com.bhukkad.common.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SecretResolver}: reads mounted secret files fresh from
 * disk (no caching) with an optional property/environment fallback.
 */
class SecretResolverTest {

    @TempDir
    Path tempDir;

    private SecretResolver resolverWith(Map<String, String> properties) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        Map<String, Object> objectProperties = new java.util.LinkedHashMap<>(properties);
        environment.getPropertySources().addLast(new MapPropertySource("test", objectProperties));
        return new SecretResolver(environment);
    }

    @Test
    void readSecretFile_returnsFileContent() throws Exception {
        Path file = tempDir.resolve("db-password");
        Files.writeString(file, "super-secret-db-password", StandardCharsets.UTF_8);

        SecretResolver resolver = resolverWith(Map.of());

        assertThat(resolver.readSecretFile(file.toString())).contains("super-secret-db-password");
    }

    @Test
    void readSecretFile_stripsTrailingNewlineAndWhitespace() throws Exception {
        Path file = tempDir.resolve("jwt-secret");
        Files.writeString(file, "  \n  token-value   \n  ", StandardCharsets.UTF_8);

        SecretResolver resolver = resolverWith(Map.of());

        assertThat(resolver.readSecretFile(file.toString()))
                .hasValue("  \n  token-value");
    }

    @Test
    void readSecretFile_emptyFile_returnsEmpty() throws Exception {
        Path file = tempDir.resolve("empty");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        SecretResolver resolver = resolverWith(Map.of());

        assertThat(resolver.readSecretFile(file.toString())).isEmpty();
    }

    @Test
    void readSecretFile_missingFile_returnsEmpty() {
        SecretResolver resolver = resolverWith(Map.of());

        assertThat(resolver.readSecretFile("/does-not-exist/db-password")).isEmpty();
    }

    @Test
    void readSecretFile_unreadableFile_returnsEmpty() throws Exception {
        Path file = tempDir.resolve("unreadable");
        Files.createFile(file);
        file.toFile().setReadable(false);

        try {
            SecretResolver resolver = resolverWith(Map.of());
            assertThat(resolver.readSecretFile(file.toString())).isEmpty();
        } finally {
            file.toFile().setReadable(true);
        }
    }

    @Test
    void resolve_fileWinsOverFallbackProperty() throws Exception {
        Path file = tempDir.resolve("db-password");
        Files.writeString(file, "file-password", StandardCharsets.UTF_8);

        SecretResolver resolver = resolverWith(Map.of("DB_PASSWORD", "env-password"));

        assertThat(resolver.resolve(file.toString(), "DB_PASSWORD"))
                .contains("file-password");
    }

    @Test
    void resolve_fallsBackToPropertyWhenFileMissing() {
        SecretResolver resolver = resolverWith(Map.of("DB_PASSWORD", "env-password"));

        assertThat(resolver.resolve("/missing/db-password", "DB_PASSWORD"))
                .contains("env-password");
    }

    @Test
    void resolve_noFileAndNoFallback_returnsEmpty() {
        SecretResolver resolver = resolverWith(Map.of());

        assertThat(resolver.resolve("/missing/db-password", null)).isEmpty();
        assertThat(resolver.resolve("/missing/db-password", "UNSET_PROPERTY")).isEmpty();
    }

    @Test
    void resolveMountedSecret_usesConfiguredMountPath() throws Exception {
        Path file = tempDir.resolve("db-password");
        Files.writeString(file, "mounted-password", StandardCharsets.UTF_8);

        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .addFirst(new MapPropertySource("mount-path", Map.of("app.secrets.mount-path", (Object) tempDir.toString())));
        SecretResolver resolver = new SecretResolver(environment);

        assertThat(resolver.resolveMountedSecret("db-password")).contains("mounted-password");
    }

    @Test
    void expandFilePlaceholders_replacesFilePlaceholder() throws Exception {
        Path file = tempDir.resolve("db-password");
        Files.writeString(file, "secret-value", StandardCharsets.UTF_8);

        SecretResolver resolver = resolverWith(Map.of());

        String result = resolver.expandFilePlaceholders("${file:" + file + ":${DB_PASSWORD:default}}");

        assertThat(result).isEqualTo("secret-value");
    }

    @Test
    void expandFilePlaceholders_leavesFallbackWhenFileMissing() {
        SecretResolver resolver = resolverWith(Map.of("DB_PASSWORD", "env-password"));

        String result = resolver.expandFilePlaceholders("${file:/missing/db-password:${DB_PASSWORD:default}}");

        assertThat(result).isEqualTo("${DB_PASSWORD:default}");
    }

    @Test
    void expandFilePlaceholders_untouchedWithoutFilePlaceholder() {
        SecretResolver resolver = resolverWith(Map.of());

        String result = resolver.expandFilePlaceholders("plain-${DB_PASSWORD:default}-text");

        assertThat(result).isEqualTo("plain-${DB_PASSWORD:default}-text");
    }

    @Test
    void expandFilePlaceholders_failsFastWithoutFallback() {
        SecretResolver resolver = resolverWith(Map.of());

        assertThatThrownBy(() -> resolver.expandFilePlaceholders("${file:/missing/db-password}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/missing/db-password");
    }

    @Test
    void resolveFileBackedProperties_collectsAllFileBackedProperties() throws Exception {
        Path dbPassword = tempDir.resolve("db-password");
        Files.writeString(dbPassword, "db-secret", StandardCharsets.UTF_8);
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .addFirst(new MapPropertySource("mount-path", Map.of("app.secrets.mount-path", (Object) tempDir.toString())));
        environment.getPropertySources().addLast(new MapPropertySource("yaml", Map.of(
                "spring.datasource.password", "${file:" + dbPassword + ":${DB_PASSWORD:default}}",
                "app.auth.jwt.secret", "${file:" + tempDir.resolve("jwt-secret") + ":${JWT_SECRET:}}",
                "spring.datasource.url", "jdbc:postgresql://db:5432/app")));

        Map<String, String> resolved = SecretResolver.resolveFileBackedProperties(environment);

        assertThat(resolved).hasSize(2);
        assertThat(resolved.keySet()).containsExactlyInAnyOrder("spring.datasource.password", "app.auth.jwt.secret");
        assertThat(resolved.get("spring.datasource.password")).isEqualTo("db-secret");
        assertThat(resolved.get("app.auth.jwt.secret")).isEqualTo("${JWT_SECRET:}");
    }
}
