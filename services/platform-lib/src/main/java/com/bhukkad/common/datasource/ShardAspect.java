package com.bhukkad.common.datasource;

import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * AOP aspect that sets the PostgreSQL {@code search_path} to the shard schema
 * for methods annotated with {@link Shard}. The schema is derived from the
 * method parameter whose name matches the {@link Shard#key()} value.
 *
 * <p>Active only under the {@code prod} profile so local/dev tests do not
 * require shard schemas to exist.</p>
 *
 * <p><b>Transaction safety:</b> when a Spring transaction is active the aspect
 * issues {@code SET LOCAL search_path}, which is transaction-scoped and
 * automatically reverts at COMMIT/ROLLBACK. When no transaction is active it
 * issues {@code SET search_path} on the single-statement connection; the
 * connection is managed by Spring and is returned to the pool with the default
 * search_path on release.</p>
 */
@Aspect
@Component
@Profile("prod")
@Slf4j
public class ShardAspect {

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final DefaultParameterNameDiscoverer parameterNameDiscoverer;

    public ShardAspect(ObjectProvider<DataSource> dataSourceProvider) {
        this(dataSourceProvider, new DefaultParameterNameDiscoverer());
    }

    ShardAspect(ObjectProvider<DataSource> dataSourceProvider, DefaultParameterNameDiscoverer parameterNameDiscoverer) {
        this.dataSourceProvider = dataSourceProvider;
        this.parameterNameDiscoverer = parameterNameDiscoverer;
    }

    @Before("@within(org.springframework.data.jpa.repository.JpaRepository) && execution(@com.bhukkad.common.datasource.Shard * *(..))")
    public void applyShard(JoinPoint jp) throws SQLException {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return;
        }
        Method method = ((MethodSignature) jp.getSignature()).getMethod();
        Shard shard = AnnotationUtils.findAnnotation(method, Shard.class);
        if (shard == null) {
            return;
        }

        Object[] args = jp.getArgs();
        if (args.length == 0) {
            return;
        }

        String keyName = shard.key();
        Object keyValue = resolveArgument(method, args, keyName);
        if (keyValue == null) {
            throw new IllegalArgumentException(
                    "Cannot resolve shard key '" + keyName + "' for " + method.getName());
        }
        Long userId = extractLong(keyValue);
        if (userId == null) {
            throw new IllegalArgumentException(
                    "Shard key '" + keyName + "' must be a numeric id for " + method.getName()
                            + " (got " + keyValue.getClass().getSimpleName() + ")");
        }

        String schema = ShardRouter.shardFor(userId);
        applySchema(schema, dataSource);
    }

    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmt")
    private Object resolveArgument(Method method, Object[] args, String keyName) {
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length && i < args.length; i++) {
                if (keyName.equals(parameterNames[i])) {
                    return args[i];
                }
            }
        }
        // Fallback: if the key matches the first parameter name or there is only
        // one argument, use positional resolution so existing single-arg sharded
        // methods continue to work without the -parameters compiler flag.
        if (parameterNames != null && parameterNames.length > 0 && keyName.equals(parameterNames[0])) {
            return args[0];
        }
        if (args.length == 1) {
            return args[0];
        }
        return null;
    }

    private void applySchema(String schema, DataSource dataSource) throws SQLException {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        if (connection == null) {
            log.warn("SHARD_SCHEMA_SKIP no_connection schema={}", schema);
            return;
        }
        boolean transactionActive = TransactionSynchronizationManager.isSynchronizationActive();
        String sql = transactionActive
                ? "SET LOCAL search_path TO " + schema + ", public"
                : "SET search_path TO " + schema + ", public";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            log.debug("SHARD_SCHEMA_SET schema={} local={}", schema, transactionActive);
        } catch (SQLException ex) {
            log.error("SHARD_SCHEMA_FAILED schema={} sql={} error={}", schema, sql, ex.getMessage());
            throw new IllegalStateException("Failed to set search_path to " + schema, ex);
        }
        // Do NOT release the connection here. Spring's DataSourceUtils manages
        // the connection lifecycle; releasing it here would unbind the thread-
        // bound connection and break the surrounding transaction.
    }

    private static Long extractLong(Object arg) {
        if (arg instanceof Long l) return l;
        if (arg instanceof String s) {
            try {
                return Long.valueOf(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (arg instanceof Integer i) return Long.valueOf(i);
        return null;
    }
}
