package com.bhukkad.datasource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

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
}
