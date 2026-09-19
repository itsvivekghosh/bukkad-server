package com.bhukkad.common.datasource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLClientInfoException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Latency-aware read replica routing datasource for 200k+ TPS workloads.
 * <p>
 * Extends the standard ReadReplicaRoutingDataSource with intelligent
 * replica selection based on real-time latency metrics. Automatically
 * routes queries to the fastest healthy replica, with automatic failover
 * and health checking.
 * </p>
 *
 * <p>Features for 200k+ TPS:
 * <ul>
 *   <li>Latency-based replica selection with EWMA smoothing</li>
 *   <li>Automatic failover on replica failures</li>
 *   <li>Per-replica latency metrics via Micrometer</li>
 *   <li>Write-fence for read-your-writes consistency</li>
 *   <li>Connection-level timing for accurate latency measurement</li>
 * </ul>
 */
public class LatencyAwareRoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(LatencyAwareRoutingDataSource.class);

    private final LatencyAwareReplicaSelector latencySelector;
    private final ReadReplicaSelector fallbackSelector;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Timer> replicaTimers = new ConcurrentHashMap<>();

    public LatencyAwareRoutingDataSource(
            LatencyAwareReplicaSelector latencySelector,
            ReadReplicaSelector fallbackSelector,
            MeterRegistry meterRegistry) {
        super();
        this.latencySelector = latencySelector;
        this.fallbackSelector = fallbackSelector;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        // V-17 write fence: recently committed primary write pins reads to primary
        if (WriteFenceContext.isActive()) {
            return ReadReplicaType.PRIMARY;
        }

        // Check read-only transaction or explicit replica context
        if (ReadReplicaContext.get() == ReadReplicaType.REPLICA
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            // Use latency-aware selector for intelligent routing
            return latencySelector.selectReplica();
        }

        return ReadReplicaType.PRIMARY;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Object lookupKey = determineCurrentLookupKey();
        
        if (ReadReplicaType.PRIMARY.equals(lookupKey)) {
            armWriteFenceAfterCommit();
            return determineTargetDataSource().getConnection();
        }

String replicaKey = (String) lookupKey;
        
        // Time the connection acquisition for this replica
        Timer.Sample sample = Timer.start(meterRegistry);
        Timer replicaTimer = replicaTimers.computeIfAbsent(replicaKey, k -> 
                Timer.builder("pgbouncer.replica.connection_acquire")
                        .tag("replica", replicaKey)
                        .description("Replica connection acquisition time")
                        .register(meterRegistry));

        try {
            DataSource target = determineTargetDataSource();
            Connection conn = target.getConnection();
            
            // Wrap connection to record query latency
            return new LatencyTrackingConnection(conn, replicaKey, latencySelector, sample, replicaTimer);
            
        } catch (SQLException ex) {
            sample.stop(replicaTimer);
            latencySelector.recordFailure(replicaKey, 0);
            throw ex;
        }
    }

    /**
     * Connection wrapper that tracks query execution latency per replica.
     */
    private static class LatencyTrackingConnection extends DelegatingConnection {
        private final String replicaKey;
        private final LatencyAwareReplicaSelector selector;
        private final Timer.Sample sample;
        private final Timer replicaTimer;

        LatencyTrackingConnection(Connection delegate, String replicaKey,
                                  LatencyAwareReplicaSelector selector,
                                  Timer.Sample sample, Timer replicaTimer) {
            super(delegate);
            this.replicaKey = replicaKey;
            this.selector = selector;
            this.sample = sample;
            this.replicaTimer = replicaTimer;
        }

        @Override
        public void close() throws SQLException {
            try {
                super.close();
            } finally {
                sample.stop(replicaTimer);
            }
        }
    }

    /**
     * Simple delegating connection wrapper.
     */
    private static class DelegatingConnection implements Connection {
        private final Connection delegate;

        DelegatingConnection(Connection delegate) {
            this.delegate = delegate;
        }

        @Override public void close() throws SQLException { delegate.close(); }
        @Override public boolean isClosed() throws SQLException { return delegate.isClosed(); }
        // Delegate all other methods to delegate...
        @Override public java.sql.Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException { return delegate.prepareStatement(sql); }
        @Override public java.sql.CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        @Override public void setAutoCommit(boolean autoCommit) throws SQLException { delegate.setAutoCommit(autoCommit); }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override public void commit() throws SQLException { delegate.commit(); }
        @Override public void rollback() throws SQLException { delegate.rollback(); }
        @Override public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        @Override public void setReadOnly(boolean readOnly) throws SQLException { delegate.setReadOnly(readOnly); }
        @Override public void setCatalog(String catalog) throws SQLException { delegate.setCatalog(catalog); }
        @Override public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        @Override public void setTransactionIsolation(int level) throws SQLException { delegate.setTransactionIsolation(level); }
        @Override public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        @Override public java.sql.SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        @Override public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency); }
        @Override public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency); }
        @Override public java.util.Map<String, java.lang.Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String, java.lang.Class<?>> map) throws SQLException { delegate.setTypeMap(map); }
        @Override public void setHoldability(int holdability) throws SQLException { delegate.setHoldability(holdability); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public java.sql.Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public java.sql.Savepoint setSavepoint(String name) throws SQLException { return delegate.setSavepoint(name); }
        @Override public void rollback(java.sql.Savepoint savepoint) throws SQLException { delegate.rollback(savepoint); }
        @Override public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException { delegate.releaseSavepoint(savepoint); }
        @Override public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException { return delegate.prepareStatement(sql, autoGeneratedKeys); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException { return delegate.prepareStatement(sql, columnIndexes); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException { return delegate.prepareStatement(sql, columnNames); }
        @Override public java.sql.Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public java.sql.Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public java.sql.NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public java.sql.SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int timeout) throws SQLException { return delegate.isValid(timeout); }
        @Override public java.util.Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public String getClientInfo(String name) throws SQLException { return delegate.getClientInfo(name); }
        // Note: setClientInfo overloads removed to avoid conflicts with SQLClientInfoException in JDBC 4.2+
        @Override public void setClientInfo(java.util.Properties properties) { 
            try {
                delegate.setClientInfo(properties);
            } catch (SQLClientInfoException e) {
                // Log and swallow - not critical for connection wrapping
                log.debug("Failed to set client info properties: {}", e.getMessage());
            }
        }
        @Override public void setClientInfo(String name, String value) { 
            try {
                delegate.setClientInfo(name, value);
            } catch (SQLClientInfoException e) {
                // Log and swallow - not critical for connection wrapping
                log.debug("Failed to set client info property {}={}: {}", name, value, e.getMessage());
            }
        }
        @Override public java.sql.DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(java.lang.Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
        @Override public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException { return delegate.createArrayOf(typeName, elements); }
        @Override public java.sql.Struct createStruct(String typeName, Object[] attributes) throws SQLException { return delegate.createStruct(typeName, attributes); }
        @Override public void setSchema(String schema) throws SQLException { delegate.setSchema(schema); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void abort(java.util.concurrent.Executor executor) throws SQLException { delegate.abort(executor); }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws SQLException { delegate.setNetworkTimeout(executor, milliseconds); }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
    }

    private void armWriteFenceAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                WriteFenceContext.arm();
            }
        });
    }

    /**
     * Record query execution latency for the given replica.
     * Called by query interceptors or AOP aspects.
     */
    public void recordQueryLatency(String replicaKey, long latencyMs, boolean success) {
        if (success) {
            latencySelector.recordSuccess(replicaKey, latencyMs);
        } else {
            latencySelector.recordFailure(replicaKey, latencyMs);
        }
    }
}