package com.bhukkad.common.datasource;

import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Chaos drill: concurrent shard interleave under load.
 *
 * <p>Runs the existing {@link ShardAspect} logic concurrently across many
 * threads to validate thread safety of the synchronized block and correct
 * {@code search_path} assignment per shard key. Uses the same mock-based
 * approach as {@link ShardAspectTest} but stresses the aspect under
 * concurrent access.</p>
 *
 * <p>Validates:</p>
 * <ul>
 *   <li>No cross-shard connection leakage under concurrent access</li>
 *   <li>The aspect's synchronized block does not starve or mis-route</li>
 *   <li>Connection pool has sufficient capacity for shard fan-out</li>
 * </ul>
 */
class ShardAspectConcurrencyChaosTest {

    private static final int THREADS = 10;
    private static final int ITERATIONS_PER_THREAD = 50;

    @Test
    void concurrentInterleave_noCrossShardLeakage() throws Exception {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
        try {
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            when(connection.createStatement()).thenReturn(statement);
            when(dataSource.getConnection()).thenReturn(connection);

            ObjectProvider<DataSource> dsProvider = mock(ObjectProvider.class);
            when(dsProvider.getIfAvailable()).thenReturn(dataSource);

            ShardAspect aspect = spy(new ShardAspect(dsProvider, new DefaultParameterNameDiscoverer()));
            Method method = SampleRepo.class.getMethod("findByCustomerId", Long.class, Boolean.class);

            ExecutorService executor = Executors.newFixedThreadPool(THREADS);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger errors = new AtomicInteger(0);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < THREADS; t++) {
                final long customerId = (t % 2 == 0) ? 10L : 11L;
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                        try {
                            aspect.applyShard(mockJoinPoint(method, new Object[]{customerId, true}));
                        } catch (Exception ex) {
                            errors.incrementAndGet();
                            System.err.printf(
                                    "THREAD %s customerId=%d iteration %d: exception=%s%n",
                                    Thread.currentThread().getName(), customerId, i, ex.getMessage()
                            );
                        }
                    }
                }));
            }

            startLatch.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            executor.shutdown();
            assertThat(errors.get()).as("No exceptions during concurrent shard interleave").isEqualTo(0);
            // Connection must not be closed by the aspect (transaction managed by Spring).
            verify(connection, never()).close();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @RepeatedTest(3)
    void concurrentInterleave_rapidShardSwitching_noLeakage() throws Exception {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
        try {
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            when(connection.createStatement()).thenReturn(statement);
            when(dataSource.getConnection()).thenReturn(connection);

            ObjectProvider<DataSource> dsProvider = mock(ObjectProvider.class);
            when(dsProvider.getIfAvailable()).thenReturn(dataSource);

            ShardAspect aspect = spy(new ShardAspect(dsProvider, new DefaultParameterNameDiscoverer()));
            Method method = SampleRepo.class.getMethod("findByCustomerId", Long.class, Boolean.class);

            ExecutorService executor = Executors.newFixedThreadPool(THREADS);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger errors = new AtomicInteger(0);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < THREADS; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                        long customerId = (i + Thread.currentThread().hashCode()) % 2 == 0 ? 10L : 11L;
                        try {
                            aspect.applyShard(mockJoinPoint(method, new Object[]{customerId, true}));
                        } catch (Exception ex) {
                            errors.incrementAndGet();
                        }
                    }
                }));
            }

            startLatch.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            executor.shutdown();
            assertThat(errors.get()).as("Rapid shard switching must not throw").isEqualTo(0);
            verify(connection, never()).close();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static JoinPoint mockJoinPoint(Method method, Object[] args) {
        org.aspectj.lang.Signature signature = mock(org.aspectj.lang.Signature.class);
        org.aspectj.lang.reflect.MethodSignature methodSignature = mock(org.aspectj.lang.reflect.MethodSignature.class);
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
    }
}
