-- ============================================================================
-- Bhukkad — platform-lib PostgreSQL baseline (P0 reference)
-- ============================================================================
-- Source of truth: docs/architecture-microservices-postgresql.md §5.2/§12 (P0).
--
-- This baseline authors the shared PLATFORM tables that EVERY microservice
-- owns locally in its own PostgreSQL database (outbox, DLQ, saga, idempotency).
-- It is the "before" of the strangler migration: the MySQL monolith keeps its
-- frozen V1..V64 set (never modified); each new service gets this baseline as
-- its V1, extended later by service-specific tables.
--
-- MySQL -> PostgreSQL type mapping applied here (plan §5.2):
--   BIGINT AUTO_INCREMENT      -> BIGINT GENERATED ALWAYS AS IDENTITY
--   DATETIME(6)                -> TIMESTAMP(6)  (see timestamp decision below)
--   TEXT                       -> TEXT
--   JSON                       -> JSONB         (saga payload / compensation)
--   ON DELETE CASCADE (FK)     -> ON DELETE CASCADE
--   FOR UPDATE SKIP LOCKED     -> unchanged (PG-identical syntax)
--
-- Timestamp decision (plan §5.4): the JPA entities for these tables map
-- LocalDateTime, which Hibernate maps to TIMESTAMP WITHOUT TIME ZONE. So the
-- common tables use TIMESTAMP(6) (no TZ), NOT TIMESTAMPTZ. TIMESTAMPTZ is
-- reserved for columns the services model as Instant (business-UTC semantics);
-- there are none in the common schema today. All values are written as UTC by
-- the application; the JDBC/JVM timezone is pinned to UTC in prod.
--
-- Uniqueness/index fidelity notes:
--   * saga_instances.saga_id has a UNIQUE constraint (entity unique=true). PG
--     backs a UNIQUE constraint with its own index, so the redundant explicit
--     idx_saga_id index is intentionally NOT created here (unlike MySQL, where
--     the UNIQUE KEY and the extra INDEX were both declared in DDL).
--   * idempotency_records carries uk_idempotency_scope_key (scope, key) as the
--     authoritative first-write-wins guard the idempotency lib relies on; the
--     Redis fast path fails open onto this constraint.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Outbox (write-side durability backbone, plan §6.1)
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type            VARCHAR(80)  NOT NULL,
    aggregate_type        VARCHAR(50)  NOT NULL,
    aggregate_id          BIGINT       NOT NULL,
    payload               TEXT         NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    retry_count           INTEGER      NOT NULL DEFAULT 0,
    last_error            VARCHAR(1000),
    created_at            TIMESTAMP(6) NOT NULL,
    published_at          TIMESTAMP(6),
    processing_started_at TIMESTAMP(6)
);

CREATE INDEX idx_outbox_status_created ON outbox_events (status, created_at);
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);

-- ---------------------------------------------------------------------------
-- Dead letter (outbox/Kafka events that exhausted retries, plan §6.2 *.dlq)
-- ---------------------------------------------------------------------------
CREATE TABLE dead_letter_events (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type     VARCHAR(80)  NOT NULL,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   BIGINT       NOT NULL,
    payload        TEXT         NOT NULL,
    last_error     VARCHAR(1000),
    retry_count    INTEGER      NOT NULL DEFAULT 0,
    source         VARCHAR(20),
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMP(6) NOT NULL,
    requeued_at    TIMESTAMP(6)
);

CREATE INDEX idx_dlq_status_created ON dead_letter_events (status, created_at);
CREATE INDEX idx_dlq_aggregate ON dead_letter_events (aggregate_type, aggregate_id);

-- ---------------------------------------------------------------------------
-- Saga instances + steps (compensating transactions, plan §6.4)
-- ---------------------------------------------------------------------------
-- NOTE: saga payload/compensation columns use TEXT, NOT JSONB, because the
-- JPA entities map these as String fields with @Column(columnDefinition =
-- "JSON") for MySQL. Hibernate binds a String property as VARCHAR, which
-- PostgreSQL rejects for jsonb columns ("column is of type jsonb but expression
-- is of type character varying"). Since the saga stores only raw JSON strings
-- with no JSONB-specific operators (plan §2.4: "raw JSON, no JSON functions"),
-- TEXT is the correct PostgreSQL type for the entity contract. When the service
-- is extracted and the entity is modelled with @JdbcTypeCode(SqlTypes.JSON),
-- the column can be promoted to JSONB.
CREATE TABLE saga_instances (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    saga_type    VARCHAR(50)  NOT NULL,
    saga_id      VARCHAR(100) NOT NULL,
    current_step VARCHAR(50),
    status       VARCHAR(20)  NOT NULL,
    payload      TEXT,
    created_at   TIMESTAMP(6) NOT NULL,
    updated_at   TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_saga_instances_saga_id UNIQUE (saga_id)
);

CREATE INDEX idx_saga_type_status ON saga_instances (saga_type, status);

CREATE TABLE saga_steps (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    saga_instance_id     BIGINT       NOT NULL,
    step_order           INTEGER      NOT NULL,
    step_name            VARCHAR(50)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    payload              TEXT,
    compensation_payload TEXT,
    error_message        VARCHAR(1000),
    created_at           TIMESTAMP(6) NOT NULL,
    updated_at           TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_saga_steps_instance FOREIGN KEY (saga_instance_id)
        REFERENCES saga_instances (id) ON DELETE CASCADE,
    CONSTRAINT uq_saga_instance_step UNIQUE (saga_instance_id, step_order)
);

CREATE INDEX idx_saga_instance_status ON saga_steps (saga_instance_id, status);
CREATE INDEX idx_saga_steps_pending ON saga_steps (status, step_order);

-- ---------------------------------------------------------------------------
-- Idempotency records (dual-layer idempotency DB guard, plan §6.1/§7)
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_records (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key  VARCHAR(128) NOT NULL,
    scope            VARCHAR(50)  NOT NULL,
    owner_id         BIGINT,
    status           VARCHAR(20)  NOT NULL,
    response_payload TEXT,
    created_at       TIMESTAMP(6) NOT NULL,
    expires_at       TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_idempotency_scope_key UNIQUE (scope, idempotency_key)
);

CREATE INDEX idx_idempotency_expires ON idempotency_records (expires_at);
