package com.bhukkad.cache;

import com.bhukkad.datasource.ReadReplicaContext;
import com.bhukkad.datasource.ReadReplicaRoutingDataSource;
import com.bhukkad.datasource.ReadReplicaType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the read/write routing decision of {@link ReadReplicaRoutingDataSource}:
 * plain reads → primary, read-only transactions → replica, read-write
 * transactions → primary, explicit {@link ReadReplicaContext} → replica.
 */
class ReadReplicaRoutingTest {

    @AfterEach
    void tearDown() {
        ReadReplicaContext.clear();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private ReadReplicaRoutingDataSource buildRouting(DataSource write, DataSource read) {
        ReadReplicaRoutingDataSource routing = new ReadReplicaRoutingDataSource();
        routing.setTargetDataSources(java.util.Map.of(
                ReadReplicaType.PRIMARY, write,
                ReadReplicaType.REPLICA, read));
        routing.setDefaultTargetDataSource(write);
        routing.afterPropertiesSet();
        return routing;
    }

    @Test
    void noTransaction_routesToPrimary() throws Exception {
        DataSource write = mock(DataSource.class);
        DataSource read = mock(DataSource.class);
        when(write.getConnection()).thenReturn(mock(Connection.class));
        when(read.getConnection()).thenReturn(mock(Connection.class));
        ReadReplicaRoutingDataSource routing = buildRouting(write, read);

        routing.getConnection();

        verify(write, atLeastOnce()).getConnection();
    }

    @Test
    void readOnlyTransaction_routesToReplica() throws Exception {
        DataSource write = mock(DataSource.class);
        DataSource read = mock(DataSource.class);
        when(write.getConnection()).thenReturn(mock(Connection.class));
        when(read.getConnection()).thenReturn(mock(Connection.class));
        ReadReplicaRoutingDataSource routing = buildRouting(write, read);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        routing.getConnection();

        verify(read, atLeastOnce()).getConnection();
    }

    @Test
    void readWriteTransaction_routesToPrimary() throws Exception {
        DataSource write = mock(DataSource.class);
        DataSource read = mock(DataSource.class);
        when(write.getConnection()).thenReturn(mock(Connection.class));
        when(read.getConnection()).thenReturn(mock(Connection.class));
        ReadReplicaRoutingDataSource routing = buildRouting(write, read);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        routing.getConnection();

        verify(write, atLeastOnce()).getConnection();
    }

    @Test
    void replicaContext_routesToReplica() throws Exception {
        DataSource write = mock(DataSource.class);
        DataSource read = mock(DataSource.class);
        when(write.getConnection()).thenReturn(mock(Connection.class));
        when(read.getConnection()).thenReturn(mock(Connection.class));
        ReadReplicaRoutingDataSource routing = buildRouting(write, read);

        ReadReplicaContext.set(ReadReplicaType.REPLICA);
        routing.getConnection();

        verify(read, atLeastOnce()).getConnection();
    }

    @Test
    void replicaContext_routesToPrimaryAfterClear() throws Exception {
        DataSource write = mock(DataSource.class);
        DataSource read = mock(DataSource.class);
        when(write.getConnection()).thenReturn(mock(Connection.class));
        when(read.getConnection()).thenReturn(mock(Connection.class));
        ReadReplicaRoutingDataSource routing = buildRouting(write, read);

        ReadReplicaContext.set(ReadReplicaType.REPLICA);
        ReadReplicaContext.clear();
        routing.getConnection();

        verify(write, atLeastOnce()).getConnection();
    }

    @Test
    void singleReplicaRepeatedlyReused() throws Exception {
        DataSource write = mock(DataSource.class);
        DataSource read = mock(DataSource.class);
        when(write.getConnection()).thenReturn(mock(Connection.class));
        when(read.getConnection()).thenReturn(mock(Connection.class));
        ReadReplicaRoutingDataSource routing = buildRouting(write, read);

        ReadReplicaContext.set(ReadReplicaType.REPLICA);
        routing.getConnection();
        routing.getConnection();

        verify(read, atLeastOnce()).getConnection();
        assertInstanceOf(ReadReplicaRoutingDataSource.class, routing);
    }
}
