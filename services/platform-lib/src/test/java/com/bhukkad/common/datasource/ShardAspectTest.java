package com.bhukkad.common.datasource;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ShardAspect} covering:
 * <ul>
 *   <li>Parameter-name-based shard key resolution (C2)</li>
 *   <li>SET LOCAL vs SET search_path selection (C1)</li>
 *   <li>No connection release by the aspect (C1)</li>
 *   <li>Fail-fast on unresolvable / non-numeric keys</li>
 *   <li>Interleave correctness: two customer IDs produce distinct schemas</li>
 * </ul>
 */
class ShardAspectTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private DefaultParameterNameDiscoverer discoverer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        discoverer = mock(DefaultParameterNameDiscoverer.class);
        when(connection.createStatement()).thenReturn(statement);
        when(dataSource.getConnection()).thenReturn(connection);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void applyShard_resolvesByParameterName() throws Exception {
        ObjectProvider<DataSource> dsProvider = mock(ObjectProvider.class);
        when(dsProvider.getIfAvailable()).thenReturn(dataSource);
        ShardAspect aspect = new ShardAspect(dsProvider, discoverer);
        Method method = SampleRepo.class.getMethod("findByCustomerId", Long.class, Boolean.class);
        when(discoverer.getParameterNames(eq(method))).thenReturn(new String[]{"customerId", "includeDeleted"});

        aspect.applyShard(mockJoinPoint(method, new Object[]{42L, true}));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).execute(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SET LOCAL search_path TO shard_10, public");
    }

    @Test
    void applyShard_fallsBackToPositionalWhenNamesMissing() throws Exception {
        ObjectProvider<DataSource> dsProvider = mock(ObjectProvider.class);
        when(dsProvider.getIfAvailable()).thenReturn(dataSource);
        ShardAspect aspect = new ShardAspect(dsProvider, discoverer);
        Method method = SampleRepo.class.getMethod("findById", Long.class);
        when(discoverer.getParameterNames(eq(method))).thenReturn(null);

        aspect.applyShard(mockJoinPoint(method, new Object[]{99L}));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).execute(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SET LOCAL search_path TO shard_3, public");
    }

    @Test
    void applyShard_throwsOnUnresolvableKey() throws Exception {
        ObjectProvider<DataSource> dsProvider = mock(ObjectProvider.class);
        when(dsProvider.getIfAvailable()).thenReturn(dataSource);
        ShardAspect aspect = new ShardAspect(dsProvider, discoverer);
        Method method = SampleRepo.class.getMethod("findByOrderNumberAndStatus", String.class, String.class);
        when(discoverer.getParameterNames(eq(method))).thenReturn(new String[]{"orderNumber", "status"});

        assertThatThrownBy(() ->
                aspect.applyShard(mockJoinPoint(method, new Object[]{"ORD-1", "ACTIVE"})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot resolve shard key 'customerId'");
    }

    @Test
    void applyShard_throwsOnNonNumericKey() throws Exception {
        ObjectProvider<DataSource> dsProvider = mock(ObjectProvider.class);
        when(dsProvider.getIfAvailable()).thenReturn(dataSource);
        ShardAspect aspect = new ShardAspect(dsProvider, discoverer);
        Method method = SampleRepo.class.getMethod("findByCustomerIdString", String.class);
        when(discoverer.getParameterNames(eq(method))).thenReturn(new String[]{"customerId"});

        assertThatThrownBy(() ->
                aspect.applyShard(mockJoinPoint(method, new Object[]{"not-a-number"})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a numeric id");
    }

    @Test
    void applyShard_usesSetSearchPathWhenNoTransaction() throws Exception {
        TransactionSynchronizationManager.clearSynchronization();
        ObjectProvider<DataSource> dsProvider = mock(ObjectProvider.class);
        when(dsProvider.getIfAvailable()).thenReturn(dataSource);
        ShardAspect aspect = new ShardAspect(dsProvider, discoverer);
        Method method = SampleRepo.class.getMethod("findByCustomerId", Long.class, Boolean.class);
        when(discoverer.getParameterNames(eq(method))).thenReturn(new String[]{"customerId", "includeDeleted"});

        aspect.applyShard(mockJoinPoint(method, new Object[]{7L, false}));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).execute(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SET search_path TO shard_7, public");
    }

    @Test
    void applyShard_doesNotReleaseConnection() throws Exception {
        ObjectProvider<DataSource> dsProvider = mock(ObjectProvider.class);
        when(dsProvider.getIfAvailable()).thenReturn(dataSource);
        ShardAspect aspect = new ShardAspect(dsProvider, discoverer);
        Method method = SampleRepo.class.getMethod("findByCustomerId", Long.class, Boolean.class);
        when(discoverer.getParameterNames(eq(method))).thenReturn(new String[]{"customerId", "includeDeleted"});

        aspect.applyShard(mockJoinPoint(method, new Object[]{1L, false}));

        verify(statement).execute(any());
        // The aspect must not close the connection; closing would break the
        // surrounding transaction managed by Spring's DataSourceUtils.
        verify(connection, never()).close();
    }

    @Test
    void interleave_twoCustomerIdsProduceDistinctSchemas() throws Exception {
        ObjectProvider<DataSource> dsProvider = mock(ObjectProvider.class);
        when(dsProvider.getIfAvailable()).thenReturn(dataSource);
        ShardAspect aspect = new ShardAspect(dsProvider, discoverer);
        Method method = SampleRepo.class.getMethod("findByCustomerId", Long.class, Boolean.class);
        when(discoverer.getParameterNames(eq(method))).thenReturn(new String[]{"customerId", "includeDeleted"});

        aspect.applyShard(mockJoinPoint(method, new Object[]{10L, true}));
        verify(statement).execute("SET LOCAL search_path TO shard_10, public");

        // Reset mock for second call (simulates a new request on the same
        // pooled connection after the first transaction committed).
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(any())).thenReturn(false);

        aspect.applyShard(mockJoinPoint(method, new Object[]{11L, true}));
        verify(statement).execute("SET LOCAL search_path TO shard_11, public");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static JoinPoint mockJoinPoint(
            Method method, Object[] args) {
        Signature signature = mock(Signature.class);
        MethodSignature methodSignature = mock(MethodSignature.class);
        when(methodSignature.getMethod()).thenReturn(method);
        when(signature.toLongString()).thenReturn(method.toGenericString());
        JoinPoint jp = mock(JoinPoint.class);
        when(jp.getSignature()).thenReturn(methodSignature);
        when(jp.getArgs()).thenReturn(args);
        return jp;
    }

    interface SampleRepo {
        @Shard(key = "customerId")
        Object findByCustomerId(Long customerId, Boolean includeDeleted);

        @Shard(key = "customerId")
        Object findById(Long id);

        @Shard(key = "customerId")
        Object findByOrderNumber(String orderNumber);

        @Shard(key = "customerId")
        Object findByCustomerIdString(String customerId);

        @Shard(key = "customerId")
        Object findByOrderNumberAndStatus(String orderNumber, String status);
    }
}
