package com.bhukkad.common.secrets;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves {@code ${file:/secrets/<name>}} placeholders in configuration values
 * before the application context is created (architecture audit §2.3).
 *
 * <p>Spring's own placeholder parser treats {@code ${file:/path}} as the
 * property {@code file} with the default value {@code /path} — it cannot read
 * files. This post-processor therefore expands the file references itself and
 * publishes the resolved values in a dedicated property source that takes
 * precedence over the YAML documents (but not over explicit command line
 * arguments).</p>
 *
 * <p>Registered through {@code META-INF/spring.factories} so it applies to every
 * service — including the reactive gateway, which does not component-scan
 * {@code com.bhukkad.common}.</p>
 */
public class SecretFileEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Name of the property source holding the resolved secret values. */
    public static final String PROPERTY_SOURCE_NAME = "bhukkadSecretFilePropertySource";

    private static final String COMMAND_LINE_ARGS = "commandLineArgs";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, String> resolved = SecretResolver.resolveFileBackedProperties(environment);
        if (resolved.isEmpty()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>(resolved);
        MutablePropertySources sources = environment.getPropertySources();
        MapPropertySource source = new MapPropertySource(PROPERTY_SOURCE_NAME, values);
        if (sources.contains(COMMAND_LINE_ARGS)) {
            // An explicit --spring.datasource.password=... on the command line
            // still wins over the mounted secret.
            sources.addAfter(COMMAND_LINE_ARGS, source);
        } else {
            sources.addFirst(source);
        }
    }

    /**
     * Runs after the config data has been loaded (the YAML documents are the
     * source of the {@code ${file:...}} references).
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
