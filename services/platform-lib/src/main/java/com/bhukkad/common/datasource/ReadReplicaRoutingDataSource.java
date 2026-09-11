package com.bhukkad.common.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
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
 *
 * <p><strong>V-17 write fence:</strong> when this datasource hands out a
 * primary connection inside an active read-write transaction, it registers a
 * {@link TransactionSynchronization} that arms the
 * {@link WriteFenceContext} thread-local fence on commit. While the fence is
 * active (age below {@code app.datasource.replica.write-fence-ms}, default
 * 2000), reads on that thread are upgraded to PRIMARY — read-your-writes
 * across the replica replay-lag budget. The fence only upgrades reads to the
 * primary, never the reverse.</p>
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
        // V-17 write fence: a recently committed primary write on this thread
        // pins reads to the primary (read-your-writes) regardless of the
        // replica context / read-only flag. Never the reverse: this branch can
        // only upgrade a would-be replica read to PRIMARY.
        if (WriteFenceContext.isActive()) {
            return ReadReplicaType.PRIMARY;
        }
        if (ReadReplicaContext.get() == ReadReplicaType.REPLICA
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return selector.next();
        }
        return ReadReplicaType.PRIMARY;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Object lookupKey = determineCurrentLookupKey();
        if (ReadReplicaType.PRIMARY.equals(lookupKey)) {
            armWriteFenceAfterCommit();
        }
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

    /**
     * V-17: arms the write fence when the CURRENT read-write transaction
     * commits, having just obtained its connection from the primary. The
     * replica pools are read-only, so a primary connection inside a
     * non-read-only transaction is the datasource-level "primary write
     * session" signal; fencing only after commit keeps the fence from leaking
     * into rolled-back work.
     */
    private void armWriteFenceAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                WriteFenceContext.arm();
            }
        });
    }

    private DataSource lookupDataSource(Object key) throws SQLException {
        Map<Object, DataSource> resolved = getResolvedDataSources();
        if (resolved != null && resolved.containsKey(key)) {
            return resolved.get(key);
        }
        return determineTargetDataSource();
    }
}
