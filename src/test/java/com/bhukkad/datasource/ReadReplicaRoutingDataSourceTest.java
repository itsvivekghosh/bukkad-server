package com.bhukkad.datasource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
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
}
