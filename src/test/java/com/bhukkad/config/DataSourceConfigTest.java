package com.bhukkad.config;

import com.bhukkad.datasource.ReadReplicaProperties;
import com.bhukkad.datasource.ReadReplicaRoutingDataSource;
import com.bhukkad.datasource.ReadReplicaType;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataSourceConfigTest {

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
        String result = invokePrivateResolveUsername(config, "replica_user", "primary_user");
        assertEquals("replica_user", result);
    }

    @Test
    void resolveUsername_usesPrimary_whenReplicaNull() throws Exception {
        String result = invokePrivateResolveUsername(config, null, "primary_user");
        assertEquals("primary_user", result);
    }

    @Test
    void resolvePassword_usesReplica_whenPresent() throws Exception {
        String result = invokePrivateResolvePassword(config, "replica_pass", "primary_pass");
        assertEquals("replica_pass", result);
    }

    @Test
    void resolvePassword_usesPrimary_whenReplicaNull() throws Exception {
        String result = invokePrivateResolvePassword(config, null, "primary_pass");
        assertEquals("primary_pass", result);
    }

    @Test
    void dataSource_noReplicaConfigured_replicaTargetFallsBackToWrite() throws Exception {
        ReadReplicaProperties props = new ReadReplicaProperties(); // enabled=false
        DataSource writeDs = mock(DataSource.class);

        DataSource result = config.dataSource(writeDs, config.readDataSource(props, writeDs, new DataSourceProperties()), props, new DataSourceProperties());

        assertInstanceOf(LazyConnectionDataSourceProxy.class, result);
        Map<Object, Object> targets = routingTargets(result);
        assertSame(writeDs, targets.get(ReadReplicaType.PRIMARY));
        assertSame(writeDs, targets.get(ReadReplicaType.REPLICA));
    }

    @Test
    void dataSource_singleReplica_createsHikariPoolForReplicaTarget() throws Exception {
        ReadReplicaProperties props = new ReadReplicaProperties();
        props.setEnabled(true);
        props.setUrl("jdbc:postgresql://replica:5432/bhukkad");
        props.setUsername("replica_user");
        props.setPassword("replica_pass");

        DataSourceProperties primary = new DataSourceProperties();
        primary.setDriverClassName("org.postgresql.Driver");

        DataSource writeDs = mock(DataSource.class);

        DataSource result = config.dataSource(writeDs, config.readDataSource(props, writeDs, primary), props, primary);

        Map<Object, Object> targets = routingTargets(result);
        DataSource replica = (DataSource) targets.get(ReadReplicaType.REPLICA);
        assertInstanceOf(HikariDataSource.class, replica);
        HikariDataSource hikari = (HikariDataSource) replica;
        assertEquals("BhukkadReadReplicaPool", hikari.getPoolName());
        assertEquals("jdbc:postgresql://replica:5432/bhukkad", hikari.getJdbcUrl());
        assertEquals("replica_user", hikari.getUsername());
        assertTrue(hikari.isReadOnly());
    }

    @Test
    void dataSource_multipleReplicas_buildsOnePoolPerEntryWithRoundRobinKeys() throws Exception {
        ReadReplicaProperties props = new ReadReplicaProperties();
        props.setEnabled(true);
        ReadReplicaProperties.Replica r1 = new ReadReplicaProperties.Replica();
        r1.setUrl("jdbc:postgresql://replica1:5432/bhukkad");
        r1.setUsername("rep1");
        ReadReplicaProperties.Replica r2 = new ReadReplicaProperties.Replica();
        r2.setUrl("jdbc:postgresql://replica2:5432/bhukkad");
        r2.setUsername("rep2");
        props.getReplicas().add(r1);
        props.getReplicas().add(r2);

        DataSourceProperties primary = new DataSourceProperties();
        primary.setDriverClassName("org.postgresql.Driver");

        DataSource writeDs = mock(DataSource.class);

        DataSource result = config.dataSource(writeDs, config.readDataSource(props, writeDs, primary), props, primary);

        Map<Object, Object> targets = routingTargets(result);
        assertSame(writeDs, targets.get(ReadReplicaType.PRIMARY));
        HikariDataSource pool0 = (HikariDataSource) targets.get("REPLICA_0");
        HikariDataSource pool1 = (HikariDataSource) targets.get("REPLICA_1");
        assertNotNull(pool0);
        assertNotNull(pool1);
        assertEquals("jdbc:postgresql://replica1:5432/bhukkad", pool0.getJdbcUrl());
        assertEquals("jdbc:postgresql://replica2:5432/bhukkad", pool1.getJdbcUrl());
        assertEquals("BhukkadReadReplicaPool-0", pool0.getPoolName());
        assertEquals("BhukkadReadReplicaPool-1", pool1.getPoolName());
    }

    @Test
    void dataSource_wrapsRoutingDataSourceInLazyProxy() throws Exception {
        ReadReplicaProperties props = new ReadReplicaProperties(); // no replica
        DataSource writeDs = mock(DataSource.class);

        DataSource result = config.dataSource(writeDs, config.readDataSource(props, writeDs, new DataSourceProperties()), props, new DataSourceProperties());

        assertInstanceOf(LazyConnectionDataSourceProxy.class, result);
    }

    /** Unwraps the routing datasource and reads its target map via reflection. */
    @SuppressWarnings("unchecked")
    private Map<Object, Object> routingTargets(DataSource result) {
        assertInstanceOf(LazyConnectionDataSourceProxy.class, result);
        DataSource target = ((LazyConnectionDataSourceProxy) result).getTargetDataSource();
        assertInstanceOf(ReadReplicaRoutingDataSource.class, target);
        return (Map<Object, Object>) ReflectionTestUtils.getField(target, "targetDataSources");
    }

    private String invokePrivateResolveUsername(DataSourceConfig config, String replicaUsername, String primaryUsername) throws Exception {
        Method method = DataSourceConfig.class.getDeclaredMethod("resolveUsername", String.class, DataSourceProperties.class);
        method.setAccessible(true);
        DataSourceProperties primary = new DataSourceProperties();
        primary.setUsername(primaryUsername);
        return (String) method.invoke(config, replicaUsername, primary);
    }

    private String invokePrivateResolvePassword(DataSourceConfig config, String replicaPassword, String primaryPassword) throws Exception {
        Method method = DataSourceConfig.class.getDeclaredMethod("resolvePassword", String.class, DataSourceProperties.class);
        method.setAccessible(true);
        DataSourceProperties primary = new DataSourceProperties();
        primary.setPassword(primaryPassword);
        return (String) method.invoke(config, replicaPassword, primary);
    }
}