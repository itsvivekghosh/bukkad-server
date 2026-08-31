package com.bhukkad.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL schema-equivalence guard (architecture-microservices-postgresql.md
 * §12 P0 — the PG variant of {@link SchemaEquivalenceIntegrationTest}).
 *
 * <p>Where the MySQL test diffs two migration sequences, this test asserts the
 * authored PG baseline ({@code db/migration-pg/V1__bhukkad_common_pg_baseline.sql})
 * actually produces the expected PostgreSQL schema: {@code GENERATED ALWAYS AS
 * IDENTITY} primary keys, {@code TIMESTAMP(6)} (no-TZ, matching the
 * LocalDateTime entities), {@code JSONB} saga columns, the unique constraints
 * the idempotency/saga libs depend on, the outbox claim indexes, and the
 * {@code ON DELETE CASCADE} FK. It also proves Flyway applied exactly one
 * successful migration (idempotent re-apply is delegated to Flyway's history
 * table).</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SchemaEquivalencePostgresIntegrationTest extends AbstractPostgresJpaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allCommonTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                        "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'",
                String.class);

        assertThat(tables).contains(
                "outbox_events",
                "dead_letter_events",
                "saga_instances",
                "saga_steps",
                "idempotency_records");
    }

    @Test
    void primaryKeys_areIdentityAlways() {
        Map<String, String>[] tables = new Map[]{
                Map.of("table", "outbox_events"),
                Map.of("table", "dead_letter_events"),
                Map.of("table", "saga_instances"),
                Map.of("table", "saga_steps"),
                Map.of("table", "idempotency_records"),
        };
        for (Map<String, String> t : tables) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT is_identity, identity_generation FROM information_schema.columns " +
                            "WHERE table_schema = current_schema() AND table_name = ? AND column_name = 'id'",
                    t.get("table"));
            assertThat(rows)
                    .as("id column of %s must be an identity column", t.get("table"))
                    .hasSize(1);
            assertThat(rows.get(0).get("is_identity")).isEqualTo("YES");
            assertThat(rows.get(0).get("identity_generation")).isEqualTo("ALWAYS");
        }
    }

    @Test
    void columnTypes_matchPostgresMapping() {
        assertColumnType("outbox_events", "id", "bigint", null);
        assertColumnType("outbox_events", "event_type", "character varying", 80);
        assertColumnType("outbox_events", "payload", "text", null);
        assertColumnType("outbox_events", "created_at", "timestamp without time zone", null);
        assertColumnType("outbox_events", "status", "character varying", 20);

        assertColumnType("dead_letter_events", "retry_count", "integer", null);
        assertColumnType("saga_instances", "saga_id", "character varying", 100);
        assertColumnType("saga_instances", "payload", "text", null);
        assertColumnType("saga_steps", "compensation_payload", "text", null);
        assertColumnType("idempotency_records", "idempotency_key", "character varying", 128);
        assertColumnType("idempotency_records", "response_payload", "text", null);
    }

    @Test
    void timestampPrecision_isMicroseconds() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT datetime_precision FROM information_schema.columns " +
                        "WHERE table_schema = current_schema() AND table_name = 'outbox_events' " +
                        "AND column_name = 'created_at'");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("datetime_precision")).isEqualTo(6);
    }

    @Test
    void uniqueConstraints_exist() {
        assertConstraint("idempotency_records", "uk_idempotency_scope_key", "UNIQUE");
        assertConstraint("saga_instances", "uq_saga_instances_saga_id", "UNIQUE");
        assertConstraint("saga_steps", "uq_saga_instance_step", "UNIQUE");
    }

    @Test
    void sagaStepsHasCascadingForeignKey() {
        List<String> rules = jdbcTemplate.queryForList(
                "SELECT rc.delete_rule FROM information_schema.referential_constraints rc " +
                        "JOIN information_schema.table_constraints tc " +
                        "  ON tc.constraint_name = rc.constraint_name " +
                        " AND tc.constraint_schema = rc.constraint_schema " +
                        "WHERE rc.constraint_schema = current_schema() AND tc.table_name = 'saga_steps' " +
                        "  AND tc.constraint_type = 'FOREIGN KEY'",
                String.class);

        assertThat(rules).containsExactly("CASCADE");
    }

    @Test
    void outboxAndDlqIndexes_exist() {
        assertIndex("outbox_events", "idx_outbox_status_created");
        assertIndex("outbox_events", "idx_outbox_aggregate");
        assertIndex("dead_letter_events", "idx_dlq_status_created");
        assertIndex("dead_letter_events", "idx_dlq_aggregate");
        assertIndex("saga_instances", "idx_saga_type_status");
        assertIndex("saga_steps", "idx_saga_instance_status");
        assertIndex("saga_steps", "idx_saga_steps_pending");
        assertIndex("idempotency_records", "idx_idempotency_expires");
    }

    @Test
    void flywayAppliedExactlySixSuccessfulMigrations() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(rows).hasSize(6);
        // Flyway 9.x stores the version column as VARCHAR on PostgreSQL.
        assertThat(rows.get(0).get("version").toString()).isEqualTo("1");
        assertThat(rows.get(0).get("success")).isEqualTo(true);
        assertThat(rows.get(1).get("version").toString()).isEqualTo("2");
        assertThat(rows.get(1).get("success")).isEqualTo(true);
        assertThat(rows.get(2).get("version").toString()).isEqualTo("3");
        assertThat(rows.get(2).get("success")).isEqualTo(true);
        assertThat(rows.get(3).get("version").toString()).isEqualTo("4");
        assertThat(rows.get(3).get("success")).isEqualTo(true);
        assertThat(rows.get(4).get("version").toString()).isEqualTo("5");
        assertThat(rows.get(4).get("success")).isEqualTo(true);
        assertThat(rows.get(5).get("version").toString()).isEqualTo("6");
        assertThat(rows.get(5).get("success")).isEqualTo(true);
    }

    private void assertColumnType(String table, String column, String expectedType, Integer maxLength) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT data_type, character_maximum_length FROM information_schema.columns " +
                        "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                table, column);
        assertThat(rows)
                .as("%s.%s must exist", table, column)
                .hasSize(1);
        assertThat(rows.get(0).get("data_type"))
                .as("%s.%s data type", table, column)
                .isEqualTo(expectedType);
        if (maxLength != null) {
            assertThat(rows.get(0).get("character_maximum_length"))
                    .as("%s.%s length", table, column)
                    .isEqualTo(maxLength);
        }
    }

    private void assertConstraint(String table, String constraint, String type) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints " +
                        "WHERE table_schema = current_schema() AND table_name = ? " +
                        "  AND constraint_type = ? AND constraint_name = ?",
                table, type, constraint);
        assertThat(rows).as("constraint %s on %s", constraint, table).hasSize(1);
    }

    private void assertIndex(String table, String index) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() " +
                        "AND tablename = ? AND indexname = ?",
                table, index);
        assertThat(rows).as("index %s on %s", index, table).hasSize(1);
    }
}
