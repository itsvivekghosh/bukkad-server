package com.bhukkad.common.secrets;

import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves secrets that the AWS Secrets Store CSI Driver mounts into the
 * container as files (architecture audit §2.3 — "Secrets Management").
 *
 * <p>Configuration uses the {@code file:} placeholder scheme:</p>
 * <pre>{@code
 * spring.datasource.password: ${file:/secrets/db-password:${DB_PASSWORD:}}
 * }</pre>
 *
 * <p>Resolution order for {@code ${file:<path>[:<fallback>]}}:</p>
 * <ol>
 *   <li>the file at {@code <path>} (read fresh from disk on every call, so a
 *       rotated secret is picked up without a restart);</li>
 *   <li>otherwise the optional fallback expression, which is left untouched so
 *       Spring resolves it against the environment (env var / property) — this
 *       keeps clusters without the CSI driver working;</li>
 *   <li>otherwise the resolution fails fast: booting with a bogus credential
 *       is worse than not booting at all.</li>
 * </ol>
 *
 * <p>Secret values are never logged — only the file path and the outcome.</p>
 */
@Component
public class SecretResolver {

    /** Placeholder prefix understood by this resolver: {@code ${file:/secrets/db-password}}. */
    public static final String FILE_PLACEHOLDER_PREFIX = "${file:";

    /** Scheme used inside the placeholder. */
    public static final String FILE_SCHEME = "file:";

    /** Default CSI mount path (see {@code k8s/secrets/secret-provider-class.yaml}). */
    public static final String DEFAULT_MOUNT_PATH = "/secrets";

    /** Property that overrides the CSI mount path. */
    public static final String MOUNT_PATH_PROPERTY = "app.secrets.mount-path";

    private static final Logger log = LoggerFactory.getLogger(SecretResolver.class);

    private final Environment environment;

    @Autowired
    public SecretResolver(Environment environment) {
        this.environment = environment;
    }

    /**
     * Reads a mounted secret file fresh from disk.
     *
     * <p>No caching on purpose: the CSI Driver rewrites the mounted file when
     * the secret is rotated in AWS Secrets Manager, and every read must see the
     * current value.</p>
     *
     * @param path absolute path of the mounted secret file
     * @return the secret value without trailing whitespace/newline, or empty
     *         when the file is missing, empty or unreadable
     */
    public Optional<String> readSecretFile(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        try {
            Path file = Path.of(path);
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                log.debug("Mounted secret file is missing or unreadable: {}", path);
                return Optional.empty();
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            // Secrets Manager values are written verbatim; only the trailing
            // newline some tooling appends is stripped.
            String value = content.stripTrailing();
            if (value.isEmpty()) {
                log.debug("Mounted secret file is empty: {}", path);
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (IOException | RuntimeException e) {
            log.debug("Mounted secret file could not be read: {} ({})", path, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Resolves a secret: the mounted file wins, otherwise the configured
     * Spring property / environment variable is used as a fallback.
     *
     * @param path            absolute path of the mounted secret file
     * @param fallbackProperty property name consulted when the file is absent
     * @return the secret value, or empty when neither the file nor the fallback
     *         property provides one
     */
    public Optional<String> resolve(@Nullable String path, @Nullable String fallbackProperty) {
        Optional<String> fromFile = readSecretFile(path);
        if (fromFile.isPresent()) {
            return fromFile;
        }
        if (fallbackProperty != null && !fallbackProperty.isBlank()) {
            String fallback = environment.getProperty(fallbackProperty);
            if (fallback != null && !fallback.isBlank()) {
                log.debug("Secret file {} not available — using property {}", path, fallbackProperty);
                return Optional.of(fallback);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves a secret mounted under the CSI mount path
     * ({@code app.secrets.mount-path}, {@value #DEFAULT_MOUNT_PATH} by default).
     *
     * @param name file name of the mounted secret, e.g. {@code db-password}
     */
    public Optional<String> resolveMountedSecret(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return readSecretFile(mountPath() + "/" + name);
    }

    /**
     * Expands every {@code ${file:...}} placeholder inside a raw configuration
     * value.
     *
     * <p>When the file is absent the fallback expression (if any) is returned
     * unchanged so that the regular Spring placeholder machinery resolves it
     * afterwards against env vars / properties. Without a fallback the
     * resolution fails fast with an {@link IllegalStateException}.</p>
     */
    public String expandFilePlaceholders(String rawValue) {
        if (rawValue == null || rawValue.isEmpty() || !rawValue.contains(FILE_PLACEHOLDER_PREFIX)) {
            return rawValue;
        }
        StringBuilder expanded = new StringBuilder(rawValue.length());
        int cursor = 0;
        while (cursor < rawValue.length()) {
            int start = rawValue.indexOf(FILE_PLACEHOLDER_PREFIX, cursor);
            if (start < 0) {
                expanded.append(rawValue, cursor, rawValue.length());
                break;
            }
            int end = findPlaceholderEnd(rawValue, start);
            if (end < 0) {
                // Unbalanced placeholder — leave the value untouched rather
                // than corrupting it; Spring reports the unresolvable placeholder.
                expanded.append(rawValue, cursor, rawValue.length());
                break;
            }
            expanded.append(rawValue, cursor, start);
            expanded.append(resolvePlaceholder(rawValue.substring(start + 2, end)));
            cursor = end + 1;
        }
        return expanded.toString();
    }

    /**
     * Collects every configuration property whose effective (highest
     * precedence) raw value contains a {@code ${file:...}} placeholder and
     * returns the expanded values.
     *
     * <p>Used at startup (see {@link SecretFileEnvironmentPostProcessor}) and by
     * the rotation refresher, which re-runs it to detect rotated secrets.
     * Sources named in {@code skipSourceNames} are ignored — the refresher uses
     * this to skip the resolved property source so it always re-scans the raw
     * configuration documents.</p>
     */
    public static Map<String, String> resolveFileBackedProperties(ConfigurableEnvironment environment,
                                                                  String... skipSourceNames) {
        Map<String, String> resolved = new LinkedHashMap<>();
        SecretResolver resolver = new SecretResolver(environment);
        Set<String> visited = new HashSet<>();
        Set<String> skipped = skipSourceNames.length == 0 ? null : Set.of(skipSourceNames);
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (skipped != null && skipped.contains(source.getName())) {
                continue;
            }
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                // Highest-precedence source wins, even when its value is not
                // file-backed — a lower source must not resurrect a stale
                // ${file:...} reference.
                if (!visited.add(name)) {
                    continue;
                }
                Object raw = source.getProperty(name);
                if (raw instanceof String text && text.contains(FILE_PLACEHOLDER_PREFIX)) {
                    resolved.put(name, resolver.expandFilePlaceholders(text));
                }
            }
        }
        return resolved;
    }

    private String resolvePlaceholder(String placeholderContent) {
        if (!placeholderContent.startsWith(FILE_SCHEME)) {
            throw new IllegalStateException("Unsupported secret placeholder: ${" + placeholderContent + "}");
        }
        String remainder = placeholderContent.substring(FILE_SCHEME.length());
        int separator = indexOfTopLevelSeparator(remainder);
        String path = separator < 0 ? remainder : remainder.substring(0, separator);
        String fallback = separator < 0 ? null : remainder.substring(separator + 1);
        Optional<String> secret = readSecretFile(path);
        if (secret.isPresent()) {
            return secret.get();
        }
        if (fallback != null && !fallback.isBlank()) {
            log.debug("Secret file {} not available — using the configured fallback", path);
            return fallback;
        }
        throw new IllegalStateException("Secret file " + path + " is missing or empty and no fallback is configured");
    }

    /** Index of the closing brace of the placeholder that starts at {@code start}, or -1. */
    private static int findPlaceholderEnd(String value, int start) {
        // The opening ${ at `start` is already open.
        int depth = 1;
        for (int i = start + 2; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '$' && i + 1 < value.length() && value.charAt(i + 1) == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Index of the first ':' that is not part of a nested placeholder, or -1. */
    private static int indexOfTopLevelSeparator(String value) {
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '$' && i + 1 < value.length() && value.charAt(i + 1) == '{') {
                depth++;
                i++;
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (c == ':' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private String mountPath() {
        String configured = environment == null ? null : environment.getProperty(MOUNT_PATH_PROPERTY);
        return configured == null || configured.isBlank() ? DEFAULT_MOUNT_PATH : configured;
    }
}
