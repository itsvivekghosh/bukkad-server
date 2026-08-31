package com.bhukkad.datasource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadReplicaRoutingDataSourceTest {

    @AfterEach
    void tearDown() {
        ReadReplicaContext.clear();
    }

    @Test
    void lookupKeyFollowsContext() {
        ReadReplicaRoutingDataSource routingDataSource = new ReadReplicaRoutingDataSource();
        DataSource primary = mock(DataSource.class);
        DataSource replica = mock(DataSource.class);
        routingDataSource.setTargetDataSources(Map.of(
                ReadReplicaType.PRIMARY, primary,
                ReadReplicaType.REPLICA, replica));
        routingDataSource.setDefaultTargetDataSource(primary);
        routingDataSource.afterPropertiesSet();

        ReadReplicaContext.set(ReadReplicaType.REPLICA);
        assertEquals(ReadReplicaType.REPLICA,
                ReflectionTestUtils.invokeMethod(routingDataSource, "determineCurrentLookupKey"));

        ReadReplicaContext.set(ReadReplicaType.PRIMARY);
        assertEquals(ReadReplicaType.PRIMARY,
                ReflectionTestUtils.invokeMethod(routingDataSource, "determineCurrentLookupKey"));
    }

    @Test
    void multiReplica_lookupKeyRoundRobinsAcrossReplicaKeys() {
        DataSource primary = mock(DataSource.class);
        ReadReplicaRoutingDataSource routingDataSource = new ReadReplicaRoutingDataSource(
                new ReadReplicaSelector(List.of("REPLICA_0", "REPLICA_1")));
        routingDataSource.setTargetDataSources(Map.of(
                ReadReplicaType.PRIMARY, primary,
                "REPLICA_0", mock(DataSource.class),
                "REPLICA_1", mock(DataSource.class)));
        routingDataSource.setDefaultTargetDataSource(primary);
        routingDataSource.afterPropertiesSet();

        ReadReplicaContext.set(ReadReplicaType.REPLICA);
        assertEquals("REPLICA_0",
                ReflectionTestUtils.invokeMethod(routingDataSource, "determineCurrentLookupKey"));
        assertEquals("REPLICA_1",
                ReflectionTestUtils.invokeMethod(routingDataSource, "determineCurrentLookupKey"));
        assertEquals("REPLICA_0",
                ReflectionTestUtils.invokeMethod(routingDataSource, "determineCurrentLookupKey"));
    }

    @Test
    void primaryContext_neverSelectsReplica() {
        DataSource primary = mock(DataSource.class);
        ReadReplicaRoutingDataSource routingDataSource = new ReadReplicaRoutingDataSource(
                new ReadReplicaSelector(List.of("REPLICA_0", "REPLICA_1")));
        routingDataSource.setTargetDataSources(Map.of(
                ReadReplicaType.PRIMARY, primary,
                "REPLICA_0", mock(DataSource.class),
                "REPLICA_1", mock(DataSource.class)));
        routingDataSource.setDefaultTargetDataSource(primary);
        routingDataSource.afterPropertiesSet();

        ReadReplicaContext.set(ReadReplicaType.PRIMARY);
        assertEquals(ReadReplicaType.PRIMARY,
                ReflectionTestUtils.invokeMethod(routingDataSource, "determineCurrentLookupKey"));
        assertEquals(ReadReplicaType.PRIMARY,
                ReflectionTestUtils.invokeMethod(routingDataSource, "determineCurrentLookupKey"));
    }

    // ==================== health-aware failover ====================

    @Test
    void getConnection_replicaFailure_failsOverToNextHealthyReplica() throws Exception {
        DataSource primary = mock(DataSource.class);
        DataSource replica0 = mock(DataSource.class);
        DataSource replica1 = mock(DataSource.class);
        Connection healthy = mock(Connection.class);
        when(replica0.getConnection()).thenThrow(new SQLException("replica 0 down"));
        when(replica1.getConnection()).thenReturn(healthy);

        ReadReplicaRoutingDataSource routingDataSource = new ReadReplicaRoutingDataSource(
                new ReadReplicaSelector(List.of("REPLICA_0", "REPLICA_1")));
        routingDataSource.setTargetDataSources(Map.of(
                ReadReplicaType.PRIMARY, primary,
                "REPLICA_0", replica0,
                "REPLICA_1", replica1));
        routingDataSource.setDefaultTargetDataSource(primary);
        routingDataSource.afterPropertiesSet();

        ReadReplicaContext.set(ReadReplicaType.REPLICA);
        assertSame(healthy, routingDataSource.getConnection());
    }

    @Test
    void getConnection_allReplicasFail_propagatesOriginalException() throws Exception {
        DataSource primary = mock(DataSource.class);
        DataSource replica0 = mock(DataSource.class);
        DataSource replica1 = mock(DataSource.class);
        when(replica0.getConnection()).thenThrow(new SQLException("replica 0 down"));
        when(replica1.getConnection()).thenThrow(new SQLException("replica 1 down"));

        ReadReplicaRoutingDataSource routingDataSource = new ReadReplicaRoutingDataSource(
                new ReadReplicaSelector(List.of("REPLICA_0", "REPLICA_1")));
        routingDataSource.setTargetDataSources(Map.of(
                ReadReplicaType.PRIMARY, primary,
                "REPLICA_0", replica0,
                "REPLICA_1", replica1));
        routingDataSource.setDefaultTargetDataSource(primary);
        routingDataSource.afterPropertiesSet();

        ReadReplicaContext.set(ReadReplicaType.REPLICA);
        SQLException ex = assertThrows(SQLException.class, routingDataSource::getConnection);
        assertEquals("replica 0 down", ex.getMessage());
    }

    @Test
    void getConnection_primaryPath_bypassesFailover() throws Exception {
        DataSource primary = mock(DataSource.class);
        Connection healthy = mock(Connection.class);
        when(primary.getConnection()).thenReturn(healthy);

        ReadReplicaRoutingDataSource routingDataSource = new ReadReplicaRoutingDataSource(
                new ReadReplicaSelector(List.of("REPLICA_0")));
        routingDataSource.setTargetDataSources(Map.of(
                ReadReplicaType.PRIMARY, primary,
                "REPLICA_0", mock(DataSource.class)));
        routingDataSource.setDefaultTargetDataSource(primary);
        routingDataSource.afterPropertiesSet();

        ReadReplicaContext.set(ReadReplicaType.PRIMARY);
        assertSame(healthy, routingDataSource.getConnection());
    }
}
