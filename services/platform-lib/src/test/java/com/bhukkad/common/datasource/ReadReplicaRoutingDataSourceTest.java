package com.bhukkad.common.datasource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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

    @AfterEach
    void restoreAmbientState() {
        ReadReplicaContext.clear();
        WriteFenceContext.clear();
        WriteFenceContext.resetClock();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
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

    // ── V-17 write fence ─────────────────────────────────────────────────────

    @Test
    void writeFence_pinsReplicaReadsToPrimary_untilTtlExpires() throws Exception {
        ReadReplicaRoutingDataSource routing = build("replica-a");
        AtomicLong clock = new AtomicLong(1000);
        WriteFenceContext.setClock(clock::get);
        WriteFenceContext.arm();
        ReadReplicaContext.set(ReadReplicaType.REPLICA);
        try {
            // within TTL: read-your-writes — reads upgrade to PRIMARY
            assertThat(routing.getConnection().getMetaData().getURL()).contains("primary");

            clock.set(1000 + 1999);
            assertThat(routing.getConnection().getMetaData().getURL()).contains("primary");

            // fence age reaches the TTL budget → replica offload resumes
            clock.set(1000 + 2000);
            assertThat(routing.getConnection().getMetaData().getURL()).contains("replica-a");
        } finally {
            ReadReplicaContext.clear();
        }
    }

    @Test
    void writeFence_overridesReadOnlyTransactionRouting() throws Exception {
        ReadReplicaRoutingDataSource routing = build("replica-a");
        WriteFenceContext.setClock(() -> 5000L);
        WriteFenceContext.arm();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            assertThat(routing.getConnection().getMetaData().getURL())
                    .as("fence only upgrades reads to primary, never the reverse")
                    .contains("primary");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void writeTxOnPrimary_armsFenceOnlyAfterCommit() throws Exception {
        ReadReplicaRoutingDataSource routing = build("replica-a");
        WriteFenceContext.setClock(() -> 1000L);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            // read-write transaction takes its connection from the primary
            assertThat(routing.getConnection().getMetaData().getURL()).contains("primary");
            assertThat(WriteFenceContext.isActive()).isFalse();

            // a replica read inside the SAME (uncommitted) tx still offloads
            ReadReplicaContext.set(ReadReplicaType.REPLICA);
            assertThat(routing.getConnection().getMetaData().getURL()).contains("replica-a");
            ReadReplicaContext.clear();

            // commit → the routing datasource's synchronization arms the fence
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(WriteFenceContext.isActive()).isTrue();

            // subsequent replica-eligible reads are pinned to the primary
            ReadReplicaContext.set(ReadReplicaType.REPLICA);
            assertThat(routing.getConnection().getMetaData().getURL()).contains("primary");
        } finally {
            ReadReplicaContext.clear();
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rolledBackWriteTx_neverArmsFence() throws Exception {
        ReadReplicaRoutingDataSource routing = build("replica-a");
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(routing.getConnection().getMetaData().getURL()).contains("primary");

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            assertThat(WriteFenceContext.isActive())
                    .as("no read-your-writes pin for rolled-back work")
                    .isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void readOnlyTxOnReplica_registersNoFenceSynchronization() throws Exception {
        ReadReplicaRoutingDataSource routing = build("replica-a");
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            assertThat(routing.getConnection().getMetaData().getURL()).contains("replica-a");
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}