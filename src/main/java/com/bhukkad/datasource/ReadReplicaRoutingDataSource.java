package com.bhukkad.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class ReadReplicaRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        if (ReadReplicaContext.get() == ReadReplicaType.REPLICA
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return ReadReplicaType.REPLICA;
        }
        return ReadReplicaType.PRIMARY;
    }
}