package com.bhukkad.admin.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Coordinates blue-green deployments with Flyway migrations.
 *
 * <p>Provides synchronization points between database migrations and
 * service deployments, enabling the Expand-Migrate-Contract pattern
 * with zero downtime.</p>
 */
@Component
@Slf4j
public class DeploymentCoordinator {

    private static final ConcurrentMap<String, MigrationState> migrationStates = new ConcurrentHashMap<>();

    public enum MigrationPhase {
        EXPAND, MIGRATE, CONTRACT, COMPLETE, ROLLED_BACK
    }

    public static class MigrationState {
        private String targetVersion;
        private MigrationPhase phase = MigrationPhase.EXPAND;
        private long startTime;
        private long endTime;
        private String error;

        public String getTargetVersion() { return targetVersion; }
        public void setTargetVersion(String targetVersion) { this.targetVersion = targetVersion; }
        public MigrationPhase getPhase() { return phase; }
        public void setPhase(MigrationPhase phase) { this.phase = phase; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public long getEndTime() { return endTime; }
        public void setEndTime(long endTime) { this.endTime = endTime; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }

    /**
     * Called when Flyway migration starts (before any migrations run).
     */
    public static void signalMigrationStart(String targetVersion) {
        MigrationState state = new MigrationState();
        state.setTargetVersion(targetVersion);
        state.setPhase(MigrationPhase.EXPAND);
        state.setStartTime(System.currentTimeMillis());
        migrationStates.put(targetVersion, state);
        log.info("DEPLOYMENT_COORDINATOR | Migration started | target={} | phase=EXPAND", targetVersion);
    }

    /**
     * Called when Flyway migration completes successfully.
     */
    public static void signalMigrationComplete(String targetVersion) {
        MigrationState state = migrationStates.get(targetVersion);
        if (state != null) {
            state.setPhase(MigrationPhase.COMPLETE);
            state.setEndTime(System.currentTimeMillis());
            log.info("DEPLOYMENT_COORDINATOR | Migration complete | target={} | duration={}ms",
                    targetVersion, state.getEndTime() - state.getStartTime());
        }
    }

    /**
     * Called when Flyway rollback starts.
     */
    public static void signalRollbackStart(String targetVersion) {
        MigrationState state = migrationStates.get(targetVersion);
        if (state != null) {
            state.setPhase(MigrationPhase.ROLLED_BACK);
            log.warn("DEPLOYMENT_COORDINATOR | Rollback started | target={}", targetVersion);
        }
    }

    /**
     * Called when Flyway rollback completes.
     */
    public static void signalRollbackComplete(String targetVersion) {
        MigrationState state = migrationStates.get(targetVersion);
        if (state != null) {
            state.setEndTime(System.currentTimeMillis());
            log.warn("DEPLOYMENT_COORDINATOR | Rollback complete | target={}", targetVersion);
        }
    }

    /**
     * Updates the current migration phase (called by deployment pipeline).
     */
    public static void setPhase(String targetVersion, MigrationPhase phase) {
        MigrationState state = migrationStates.get(targetVersion);
        if (state != null) {
            state.setPhase(phase);
            log.info("DEPLOYMENT_COORDINATOR | Phase changed | target={} | phase={}", targetVersion, phase);
        }
    }

    /**
     * Gets the current migration state.
     */
    public static MigrationState getState(String targetVersion) {
        return migrationStates.get(targetVersion);
    }

    /**
     * Checks if migration is in a specific phase.
     */
    public static boolean isInPhase(String targetVersion, MigrationPhase phase) {
        MigrationState state = migrationStates.get(targetVersion);
        return state != null && state.getPhase() == phase;
    }

    /**
     * Waits for migration to reach a specific phase (with timeout).
     */
    public static boolean waitForPhase(String targetVersion, MigrationPhase phase, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isInPhase(targetVersion, phase)) {
                return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Records an error during migration.
     */
    public static void recordError(String targetVersion, String error) {
        MigrationState state = migrationStates.get(targetVersion);
        if (state != null) {
            state.setError(error);
            log.error("DEPLOYMENT_COORDINATOR | Migration error | target={} | error={}", targetVersion, error);
        }
    }

    /**
     * Cleans up migration state after deployment completes.
     */
    public static void cleanup(String targetVersion) {
        migrationStates.remove(targetVersion);
    }
}