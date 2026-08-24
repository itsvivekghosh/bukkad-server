package com.bhukkad.config;

import com.bhukkad.datasource.ReadReplicaProperties;
import com.bhukkad.datasource.ReadReplicaRoutingDataSource;
import com.bhukkad.datasource.ReadReplicaType;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataSourceConfigTest {

    @Mock
    private ReadReplicaProperties replicaProperties;
    @Mock
    private DataSourceProperties dataSourceProperties;

    private DataSourceConfig config;

    @BeforeEach
    void setUp() {
        config = new DataSourceConfig();
    }

    @Test
    void dataSourceProperties_returnsNewInstance() {
        DataSourceProperties props = config.dataSourceProperties();
        assertNotNull(props);
    }

    @Test
    void resolveUsername_usesReplica_whenPresent() throws Exception {
        ReadReplicaProperties props = new ReadReplicaProperties();
        props.setUsername("replica_user");
        DataSourceProperties primary = new DataSourceProperties();
        primary.setUsername("primary_user");

        String result = invokePrivateResolveUsername(config, props, primary);

        assertEquals("replica_user", result);
    }

    @Test
    void resolveUsername_usesPrimary_whenReplicaNull() throws Exception {
        ReadReplicaProperties props = new ReadReplicaProperties();
        props.setUsername(null);
        DataSourceProperties primary = new DataSourceProperties();
        primary.setUsername("primary_user");

        String result = invokePrivateResolveUsername(config, props, primary);

        assertEquals("primary_user", result);
    }

    @Test
    void resolvePassword_usesReplica_whenPresent() throws Exception {
        ReadReplicaProperties props = new ReadReplicaProperties();
        props.setPassword("replica_pass");
        DataSourceProperties primary = new DataSourceProperties();
        primary.setPassword("primary_pass");

        String result = invokePrivateResolvePassword(config, props, primary);

        assertEquals("replica_pass", result);
    }

    @Test
    void resolvePassword_usesPrimary_whenReplicaNull() throws Exception {
        ReadReplicaProperties props = new ReadReplicaProperties();
        props.setPassword(null);
        DataSourceProperties primary = new DataSourceProperties();
        primary.setPassword("primary_pass");

        String result = invokePrivateResolvePassword(config, props, primary);

        assertEquals("primary_pass", result);
    }

    @Test
    void dataSource_createsRoutingDataSource() {
        // Skip - requires actual DB driver
    }

    @Test
    void readDataSource_returnsWriteDataSource_whenReplicaNotConfigured() {
        when(replicaProperties.isConfigured()).thenReturn(false);
        DataSource writeDs = mock(DataSource.class);

        DataSource readDs = config.readDataSource(replicaProperties, writeDs, dataSourceProperties);

        assertSame(writeDs, readDs);
    }

    @Test
    void readDataSource_createsReplicaPool_whenConfigured() {
        ReadReplicaProperties props = new ReadReplicaProperties();
        props.setEnabled(true);
        props.setUrl("jdbc:mysql://replica:3306/bhukkad");
        props.setUsername("replica_user");
        props.setPassword("replica_pass");

        DataSourceProperties primary = new DataSourceProperties();
        primary.setDriverClassName("com.mysql.cj.jdbc.Driver");

        DataSource writeDs = mock(DataSource.class);

        DataSource readDs = config.readDataSource(props, writeDs, primary);

        assertInstanceOf(HikariDataSource.class, readDs);
        HikariDataSource replica = (HikariDataSource) readDs;
        assertEquals("BhukkadReadReplicaPool", replica.getPoolName());
        assertEquals("jdbc:mysql://replica:3306/bhukkad", replica.getJdbcUrl());
        assertEquals("replica_user", replica.getUsername());
        assertTrue(replica.isReadOnly());
    }

    @Test
    void dataSource_wrapsRoutingDataSourceInLazyProxy() throws Exception {
        DataSource writeDs = mock(DataSource.class);
        DataSource readDs = mock(DataSource.class);
        // LazyConnectionDataSourceProxy resolves default connection properties
        // (autocommit, transaction isolation) from a probe connection.
        Connection probe = mock(Connection.class);
        when(probe.getAutoCommit()).thenReturn(true);
        when(writeDs.getConnection()).thenReturn(probe);

        DataSource result = config.dataSource(writeDs, readDs);

        // The primary bean is a LazyConnectionDataSourceProxy deferring connection
        // acquisition until the first statement, so routing can pick read/write.
        assertInstanceOf(LazyConnectionDataSourceProxy.class, result);
    }

    private String invokePrivateResolveUsername(DataSourceConfig config, ReadReplicaProperties replica, DataSourceProperties primary) throws Exception {
        Method method = DataSourceConfig.class.getDeclaredMethod("resolveUsername", ReadReplicaProperties.class, DataSourceProperties.class);
        method.setAccessible(true);
        return (String) method.invoke(config, replica, primary);
    }

    private String invokePrivateResolvePassword(DataSourceConfig config, ReadReplicaProperties replica, DataSourceProperties primary) throws Exception {
        Method method = DataSourceConfig.class.getDeclaredMethod("resolvePassword", ReadReplicaProperties.class, DataSourceProperties.class);
        method.setAccessible(true);
        return (String) method.invoke(config, replica, primary);
    }
}