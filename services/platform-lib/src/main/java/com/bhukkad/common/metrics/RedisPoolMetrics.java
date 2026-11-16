package com.bhukkad.common.metrics;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

/**
 * Exposes Micrometer gauges for the Lettuce Redis connection-pool state.
 *
 * <p>Spring Boot's {@code LettuceMetricsAutoConfiguration} only registers
 * Lettuce command-latency metrics; it does <em>not</em> register connection
 * pool gauges (e.g. {@code lettuce.connections.active}, which Prometheus
 * scrapes as {@code lettuce_connections_active}). This component fills that
 * gap by reflectively reading the pooled connections held by the
 * {@link LettuceConnectionFactory} and registering one gauge per pool
 * statistic.</p>
 *
 * <p>Duplicate-gauge safety: before registering each gauge we check whether a
 * meter with the same name already exists in the registry (for example when
 * the actuator's own Lettuce binder exposes it) and skip registration if so.
 * This avoids {@code IllegalArgumentException} from Micrometer, which throws
 * on duplicate gauge names. All pool reads are wrapped in try/catch so a
 * reflection or Redis issue can never break startup or the metrics path.</p>
 */
@Component
@ConditionalOnClass(LettuceConnectionFactory.class)
public class RedisPoolMetrics {

    private static final Logger log = LoggerFactory.getLogger(RedisPoolMetrics.class);

    private static final String CONNECTION_PROVIDER_FIELD = "connectionProvider";
    private static final String POOLS_FIELD = "pools";

    private final MeterRegistry meterRegistry;
    private final LettuceConnectionFactory factory;

    // Reflectively resolved handles to the Lettuce connection pool(s), cached
    // after first successful resolution so metric scrapes stay cheap.
    private volatile Field connectionProviderField;
    private volatile Field poolsField;
    private volatile Object connectionProvider;

    public RedisPoolMetrics(ObjectProvider<LettuceConnectionFactory> factoryProvider,
                            MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.factory = factoryProvider.getIfAvailable();
        if (this.factory == null) {
            return;
        }
        registerGauges();
    }

    private void registerGauges() {
        registerIfAbsent("lettuce.connections.active", () -> poolStatistic("getNumActive"));
        registerIfAbsent("lettuce.connections.idle", () -> poolStatistic("getNumIdle"));
        registerIfAbsent("lettuce.connections.max", () -> poolStatistic("getMaxTotal"));
        registerIfAbsent("lettuce.connections.pending", () -> poolStatistic("getNumWaiters"));
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> readPoolsMap() {
        try {
            Object cp = resolveConnectionProvider();
            if (cp == null) {
                return null;
            }
            Field poolsField = resolvePoolsField(cp.getClass());
            if (poolsField == null) {
                return null;
            }
            Object value = poolsField.get(cp);
            if (value instanceof Map<?, ?> map) {
                return map;
            }
            return null;
        } catch (Throwable t) {
            log.debug("REDIS_POOL_METRIC_REFLECTION_FAILED error={}", t.getMessage());
            return null;
        }
    }

    private Object resolveConnectionProvider() throws Exception {
        if (connectionProvider != null) {
            return connectionProvider;
        }
        if (connectionProviderField == null) {
            connectionProviderField = LettuceConnectionFactory.class.getDeclaredField(CONNECTION_PROVIDER_FIELD);
            connectionProviderField.setAccessible(true);
        }
        Object cp = connectionProviderField.get(factory);
        connectionProvider = cp;
        return cp;
    }

    private Field resolvePoolsField(Class<?> providerClass) throws Exception {
        if (poolsField != null) {
            return poolsField;
        }
        Field field = providerClass.getDeclaredField(POOLS_FIELD);
        field.setAccessible(true);
        poolsField = field;
        return field;
    }

    private double poolStatistic(String methodName) {
        Map<?, ?> pools = readPoolsMap();
        if (pools == null || pools.isEmpty()) {
            return Double.NaN;
        }
        long total = 0;
        boolean anyResolved = false;
        for (Object pool : pools.values()) {
            try {
                Method method = pool.getClass().getMethod(methodName);
                Object result = method.invoke(pool);
                if (result instanceof Number number) {
                    total += number.longValue();
                    anyResolved = true;
                }
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                // Skip a pool whose statistic cannot be read reflectively.
                log.debug("REDIS_POOL_METRIC_STATISTIC_FAILED method={} error={}",
                        methodName, e.getMessage());
            }
        }
        return anyResolved ? total : Double.NaN;
    }

    private void registerIfAbsent(String name, java.util.function.DoubleSupplier statistic) {
        if (meterRegistry.find(name).meter() != null) {
            log.debug("REDIS_POOL_METRIC_SKIP_DUPLICATE gauge={}", name);
            return;
        }
        try {
            Gauge.builder(name, this, ignored -> statistic.getAsDouble()).register(meterRegistry);
            log.debug("REDIS_POOL_METRIC_REGISTERED gauge={}", name);
        } catch (IllegalArgumentException e) {
            // Registered concurrently or already present under a different tag set;
            // never let a metrics registration failure affect the application.
            log.debug("REDIS_POOL_METRIC_DUPLICATE gauge={}", name);
        }
    }
}