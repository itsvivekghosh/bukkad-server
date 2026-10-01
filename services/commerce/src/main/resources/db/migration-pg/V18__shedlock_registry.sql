-- Order service schedulers (StuckOrderSweep, ScheduledOrderProcessor,
-- CartRecoveryService) run under @SchedulerLock; the platform LockProvider
-- (SchedulerLockConfig) requires the shedlock registry table, which the order
-- schema never had — the async-saga recovery chaos IT reproduced the runtime
-- failure (BadSqlGrammarException: relation "shedlock" does not exist).
-- Standard shedlock 5.x schema (same as delivery V9 / search V10 / growth V10).

CREATE TABLE IF NOT EXISTS public.shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
