package com.bhukkad.common.db;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Flyway callback for blue-green migration coordination.
 *
 * <p>Signals deployment system when migrations start and complete,
 * enabling coordinated blue-green deployments where schema changes
 * are split into Expand/Migrate/Contract phases.</p>
 */
@Component
public class MigrationCallbacks implements Callback {

    private static final Logger log = LoggerFactory.getLogger(MigrationCallbacks.class);

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_MIGRATE || event == Event.AFTER_MIGRATE
                || event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE
                || event == Event.BEFORE_UNDO || event == Event.AFTER_UNDO;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        // Run coordination outside transaction
        return false;
    }

    @Override
    public void handle(Event event, Context context) {
        String target = context.getConfiguration().getTarget() != null
                ? context.getConfiguration().getTarget().toString()
                : "latest";

        switch (event) {
            case BEFORE_MIGRATE -> {
                log.info("FLYWAY_MIGRATION_START | target={} | migration_count={}",
                        target, context.getConfiguration().getLocations().length);
                DeploymentCoordinator.signalMigrationStart(target);
            }
            case AFTER_MIGRATE -> {
                log.info("FLYWAY_MIGRATION_COMPLETE | target={} | success=true", target);
                DeploymentCoordinator.signalMigrationComplete(target);
            }
            case BEFORE_EACH_MIGRATE -> {
                String migration = context.getMigrationInfo() != null
                        ? context.getMigrationInfo().getDescription()
                        : "unknown";
                log.info("FLYWAY_MIGRATION_STEP_START | migration={} | version={}",
                        migration, context.getMigrationInfo() != null
                                ? context.getMigrationInfo().getVersion() : "?");
            }
            case AFTER_EACH_MIGRATE -> {
                String migration = context.getMigrationInfo() != null
                        ? context.getMigrationInfo().getDescription()
                        : "unknown";
                log.info("FLYWAY_MIGRATION_STEP_COMPLETE | migration={}", migration);
            }
            case BEFORE_UNDO -> {
                log.warn("FLYWAY_UNDO_START | target={}", target);
                DeploymentCoordinator.signalRollbackStart(target);
            }
            case AFTER_UNDO -> {
                log.warn("FLYWAY_UNDO_COMPLETE | target={}", target);
                DeploymentCoordinator.signalRollbackComplete(target);
            }
        }
    }

    @Override
    public String getCallbackName() {
        return "BlueGreenMigrationCallback";
    }
}