package com.bhukkad.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Routes JDBC connections to the primary or a read replica based on the
 * {@link ReadReplicaContext} thread-local and the current transaction's
 * read-only flag. When multiple replicas are configured, connections are
 * round-robined across them via {@link ReadReplicaSelector}.
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
}
