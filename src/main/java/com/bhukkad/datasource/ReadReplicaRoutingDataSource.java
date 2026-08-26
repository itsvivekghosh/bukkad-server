package com.bhukkad.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * Routes JDBC connections to the primary or a read replica based on the
 * {@link ReadReplicaContext} thread-local and the current transaction's
 * read-only flag. When multiple replicas are configured, connections are
 * round-robined across them via {@link ReadReplicaSelector}.
 *
 * <p>Health-aware failover: when a replica's {@code getConnection()} throws,
 * the replica is marked unavailable (cooldown) and the request is retried once
 * against the next healthy replica before the error propagates. Reads are
 * served even while a replica is being replaced or restarted.
 */
public class ReadReplicaRoutingDataSource extends AbstractRoutingDataSource {

    /** Fallback selector: single default replica key (legacy behaviour). */
    private final ReadReplicaSelector selector;

    public ReadReplicaRoutingDataSource() {
        this(new ReadReplicaSelector(java.util.List.of()));
    }

    public ReadReplicaRoutingDataSource(ReadReplicaSelector selector) {
        super();
        this.selector = selector;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        if (ReadReplicaContext.get() == ReadReplicaType.REPLICA
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return selector.next();
        }
        return ReadReplicaType.PRIMARY;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Object lookupKey = determineCurrentLookupKey();
        if (!(lookupKey instanceof String replicaKey)) {
            // Primary or legacy single-replica path: no failover target.
            return determineTargetDataSource().getConnection();
        }
        try {
            return lookupDataSource(replicaKey).getConnection();
        } catch (SQLException ex) {
            selector.markUnavailable(replicaKey);
            // Retry once on the next healthy replica; a second failure is
            // reported as the original replica's exception (the fleet is
            // genuinely down).
            Object fallback = selector.next();
            if (fallback instanceof String fallbackKey && !fallbackKey.equals(replicaKey)) {
                try {
                    return lookupDataSource(fallbackKey).getConnection();
                } catch (SQLException ignored) {
                    // fall through
                }
            }
            throw ex;
        }
    }

    private DataSource lookupDataSource(Object key) throws SQLException {
        Map<Object, DataSource> resolved = getResolvedDataSources();
        if (resolved != null && resolved.containsKey(key)) {
            return resolved.get(key);
        }
        return determineTargetDataSource();
    }
}
