package com.bhukkad.cache;

import com.bhukkad.config.DataSourceConfig;
import com.bhukkad.datasource.ReadReplicaContext;
import com.bhukkad.datasource.ReadReplicaProperties;
import com.bhukkad.datasource.ReadReplicaType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadReplicaRoutingTest {

    private final DataSourceConfig config = new DataSourceConfig();

    @AfterEach
    void tearDown() {
        ReadReplicaContext.clear();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private AbstractRoutingDataSource buildRouting(DataSource write, DataSource read) throws Exception {
        when(write.getConnection()).thenReturn(mock(Connection.class));
        DataSource result = config.dataSource(write, read);
        assertInstanceOf(LazyConnectionDataSourceProxy.class, result);
        return (AbstractRoutingDataSource) ((LazyConnectionDataSourceProxy) result).getTargetDataSource();
    }

    @Test
    void noTransaction_routesToPrimary() throws Exception {
        DataSource write = mock(DataSource.class);
        DataSource read = mock(DataSource.class);
        AbstractRoutingDataSource routing = buildRouting(write, read);

        routing.getConnection();

        verify(write, atLeastOnce()).getConnection();
    }

    @Test
    void readOnlyTransaction_routesToReplica() throws Exception {
        DataSource write = mock(DataSource.class);
        DataSource read = mock(DataSource.class);
        AbstractRoutingDataSource routing = buildRouting(write, read);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        routing.getConnection();

        verify(read, atLeastOnce()).getConnection();
    }

    @Test
    void readWriteTransaction_routesToPrimary() throws Exception {
        DataSource write = mock(DataSource.class);
        DataSource read = mock(DataSource.class);
        AbstractRoutingDataSource routing = buildRouting(write, read);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        routing.getConnection();

        verify(write, atLeastOnce()).getConnection();
    }

    @Test
    void replicaContext_routesToReplica() throws Exception {
        DataSource write = mock(DataSource.class);
        DataSource read = mock(DataSource.class);
        AbstractRoutingDataSource routing = buildRouting(write, read);

        ReadReplicaContext.set(ReadReplicaType.REPLICA);
        routing.getConnection();

        verify(read, atLeastOnce()).getConnection();
    }

    @Test
    void replicaNotConfigured_routesReadsToPrimary() throws Exception {
        ReadReplicaProperties replicaProperties = new ReadReplicaProperties();
        DataSource write = mock(DataSource.class);
        when(write.getConnection()).thenReturn(mock(Connection.class));

        DataSource read = config.readDataSource(replicaProperties, write, new DataSourceProperties());
        assertSame(write, read, "unconfigured replica must fall back to the primary datasource");

        DataSource result = config.dataSource(write, read);
        AbstractRoutingDataSource routing =
                (AbstractRoutingDataSource) ((LazyConnectionDataSourceProxy) result).getTargetDataSource();

        ReadReplicaContext.set(ReadReplicaType.REPLICA);
        routing.getConnection();

        verify(write, atLeastOnce()).getConnection();
    }
}