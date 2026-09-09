package com.bhukkad.common.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * Routes JDBC connections to PRIMARY or a REPLICA based on the
 * {@link ReadReplicaContext} thread-local and the current transaction's
 * read-only flag. Port of the monolith's
 * {@code com.bhukkad.datasource.ReadReplicaRoutingDataSource} — the key PG
 * consideration is that read-heavy endpoints (search, browse, dashboards) must
 * hit replicas while the single-writer money path stays on PRIMARY.
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
            return determineTargetDataSource().getConnection();
        }
        try {
            return lookupDataSource(replicaKey).getConnection();
        } catch (SQLException ex) {
            selector.markUnavailable(replicaKey);
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
