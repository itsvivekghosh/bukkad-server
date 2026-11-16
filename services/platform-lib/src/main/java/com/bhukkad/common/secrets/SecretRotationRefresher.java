package com.bhukkad.common.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationPropertiesBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes rotated secrets into a running application (architecture audit §2.3
 * — "Rotation: services reload via {@code @ConfigurationProperties} refresh").
 *
 * <p>The CSI Driver rewrites the mounted file when the secret changes in AWS
 * Secrets Manager. This refresher re-reads those files on a fixed interval and,
 * for every property whose value changed:</p>
 * <ul>
 *   <li>updates the resolved property source, so every subsequent
 *       {@code environment.getProperty(...)} sees the new value;</li>
 *   <li>re-binds the affected {@code @ConfigurationProperties} beans, so
 *       consumers such as {@code PlatformJwtProperties} (JWT validation) and
 *       {@code ServiceAuthProperties} (service-to-service tokens) pick the new
 *       secret up without a restart.</li>
 * </ul>
 *
 * <p>Known limitation: JDBC pools bind their credentials when the pool is
 * created, so a rotated <em>database</em> password needs a rolling restart of
 * the pod. The refresher logs that explicitly instead of pretending the pool
 * was rotated.</p>
 *
 * <p>Only property names are logged — never secret values.</p>
 */
public class SecretRotationRefresher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SecretRotationRefresher.class);

    private static final String DATA_SOURCE_PASSWORD = "spring.datasource.password";

    private final ConfigurableEnvironment environment;
    private final ApplicationContext applicationContext;
    private final ScheduledExecutorService scheduler;

    public SecretRotationRefresher(ConfigurableEnvironment environment,
                                    ApplicationContext applicationContext,
                                    Duration interval) {
        this.environment = environment;
        this.applicationContext = applicationContext;
        long periodMillis = Math.max(1_000L, interval.toMillis());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bhukkad-secret-rotator");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::refreshSafely, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /** Re-reads the mounted secrets and applies every change. */
    public void refreshSafely() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("Secret rotation refresh failed: {}", e.getMessage());
        }
    }

    /**
     * Re-reads the mounted secret files and pushes changed values into the
     * environment and the bound configuration properties.
     *
     * @return the names of the configuration properties whose value changed
     */
    public List<String> refresh() {
        Map<String, String> resolved = SecretResolver.resolveFileBackedProperties(environment,
                SecretFileEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        if (resolved.isEmpty()) {
            return List.of();
        }
        PropertySource<?> source = environment.getPropertySources()
                .get(SecretFileEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        if (!(source instanceof MapPropertySource mapSource)) {
            return List.of();
        }
        Map<String, Object> current = mapSource.getSource();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            Object existing = current.get(entry.getKey());
            if (entry.getValue().equals(existing)) {
                continue;
            }
            current.put(entry.getKey(), entry.getValue());
            changed.add(entry.getKey());
        }
        if (changed.isEmpty()) {
            return changed;
        }
        rebindConfigurationProperties(changed);
        if (changed.contains(DATA_SOURCE_PASSWORD)) {
            log.warn("Database password rotated — JDBC pools bind credentials at creation, "
                    + "so the pod must be rolled out to use the new password");
        }
        log.info("Secret rotation applied to {} configuration propert(ies): {}", changed.size(), changed);
        return changed;
    }

    private void rebindConfigurationProperties(List<String> changed) {
        Binder binder = Binder.get(environment);
        for (ConfigurationPropertiesBean bean : ConfigurationPropertiesBean.getAll(applicationContext).values()) {
            String prefix = bean.getAnnotation().prefix();
            if (prefix == null || prefix.isBlank() || !isAffected(prefix, changed)) {
                continue;
            }
            Object instance = bean.getInstance();
            if (instance == null || instance.getClass().isRecord()) {
                // Immutable holder — cannot be re-bound in place.
                continue;
            }
            try {
                binder.bind(prefix, Bindable.ofInstance(instance));
            } catch (RuntimeException e) {
                log.warn("Could not rebind configuration properties '{}' after secret rotation: {}",
                        prefix, e.getMessage());
            }
        }
    }

    private static boolean isAffected(String prefix, List<String> changed) {
        String namespace = prefix.endsWith(".") ? prefix : prefix + ".";
        for (String property : changed) {
            if (property.equals(prefix) || property.startsWith(namespace)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
