package com.bhukkad.common.datasource;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadReplicaRoutingDataSourceTest {

    private static DataSource mockDs(String name) throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(meta.getURL()).thenReturn("jdbc:postgresql://" + name + ":5432/db");
        when(conn.getMetaData()).thenReturn(meta);
        when(ds.getConnection()).thenReturn(conn);
        return ds;
    }

    private ReadReplicaRoutingDataSource build(String... replicas) throws SQLException {
        ReadReplicaRoutingDataSource routing = new ReadReplicaRoutingDataSource(new ReadReplicaSelector(java.util.List.of(replicas)));
        Map<Object, Object> targets = new HashMap<>();
        targets.put(ReadReplicaType.PRIMARY, mockDs("primary"));
        for (String r : replicas) {
            targets.put(r, mockDs(r));
        }
        routing.setDefaultTargetDataSource(mockDs("primary"));
        routing.setTargetDataSources(targets);
        routing.afterPropertiesSet();
        return routing;
    }

    @Test
    void defaultContext_routesToPrimary() throws Exception {
        ReadReplicaRoutingDataSource routing = build("replica-a");
        assertThat(routing.getConnection().getMetaData().getURL()).contains("primary");
    }

    @Test
    void replicaContext_routesToReplica() throws Exception {
        ReadReplicaRoutingDataSource routing = build("replica-a");
        ReadReplicaContext.set(ReadReplicaType.REPLICA);
        try {
            assertThat(routing.getConnection().getMetaData().getURL()).contains("replica-a");
        } finally {
            ReadReplicaContext.clear();
        }
    }

    @Test
    void readOnlyTransaction_routesToReplica() throws Exception {
        ReadReplicaRoutingDataSource routing = build("replica-a");
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            assertThat(routing.getConnection().getMetaData().getURL()).contains("replica-a");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void noReplicasConfigured_fallsBackToPrimary() throws Exception {
        ReadReplicaRoutingDataSource routing = build();
        ReadReplicaContext.set(ReadReplicaType.REPLICA);
        try {
            assertThat(routing.getConnection().getMetaData().getURL()).contains("primary");
        } finally {
            ReadReplicaContext.clear();
        }
    }
}