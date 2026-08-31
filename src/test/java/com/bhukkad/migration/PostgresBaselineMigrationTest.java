package com.bhukkad.migration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level guardrail for the PostgreSQL baseline migration
 * ({@code db/migration-pg/V1__monolith_pg_baseline.sql}).
 *
 * <p>This is intentionally a plain unit test (no Spring, no Docker): it fails
 * fast — in the same module and at the same speed as every other unit test —
 * if the "frozen" PG baseline drifts back to MySQL-only constructs. Because the
 * monolith's MySQL migrations are frozen and PG modules get fresh baselines
 * (architecture-microservices-postgresql.md §5.3), this guard is the cheapest
 * line of defence against a future edit re-introducing {@code AUTO_INCREMENT},
 * {@code DATETIME}, {@code ENGINE=InnoDB} or similar into PostgreSQL DDL.</p>
 */
class PostgresBaselineMigrationTest {

    private static final String MIGRATION_PATH = "/db/migration-pg/V1__monolith_pg_baseline.sql";

    private static String sql;

    @BeforeAll
    static void loadMigration() throws IOException {
        try (InputStream in = PostgresBaselineMigrationTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("migration %s must exist on the classpath", MIGRATION_PATH).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void definesAllFiveCommonTables() {
        for (String table : List.of("outbox_events", "dead_letter_events",
                "saga_instances", "saga_steps", "idempotency_records")) {
            assertThat(sql).as("CREATE TABLE for %s", table)
                    .contains("CREATE TABLE " + table);
        }
    }

    @Test
    void everyPrimaryKeyUsesIdentity() {
        // Comments are stripped: the header's type-mapping table documents the
        // MySQL -> PG migration and legitimately mentions AUTO_INCREMENT.
        String ddl = stripComments(sql);
        long identityCount = countOccurrences(ddl, "BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY");
        assertThat(identityCount).isEqualTo(5);
        assertThat(ddl).doesNotContain("AUTO_INCREMENT");
    }

    @Test
    void containsNoMySqlOnlyConstructs() {
        String ddl = stripComments(sql);
        for (String forbidden : List.of(
                "AUTO_INCREMENT",        // -> GENERATED ALWAYS AS IDENTITY
                "DATETIME",              // -> TIMESTAMP(6)
                "ENGINE=InnoDB",         // PG default storage
                "ENGINE = InnoDB",
                "BIT(",                  // -> BOOLEAN
                "TINYINT(",              // -> BOOLEAN
                "MEDIUMTEXT",            // -> TEXT
                "ON DUPLICATE KEY",      // -> ON CONFLICT
                "CHARACTER SET",         // encoding is per-database in PG
                "`"                      // no backtick-quoted identifiers
        )) {
            assertThat(ddl).as("forbidden MySQL construct '%s'", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    @Test
    void usesPostgresTimestampSemantics() {
        assertThat(sql).contains("TIMESTAMP(6)");
        // Common tables model LocalDateTime (TIMESTAMP WITHOUT TIME ZONE);
        // TIMESTAMPTZ is reserved for Instant-modeled columns. Asserting the
        // no-TZ form here guards the explicit decision documented in the file.
        assertThat(sql).contains("TIMESTAMP(6) NOT NULL");
    }

    @Test
    void sagaPayloadColumnsAreText_notJsonb() {
        // The saga entities map payload/compensationPayload as String
        // (MySQL columnDefinition="JSON"). Hibernate binds a String as VARCHAR,
        // which PostgreSQL rejects for jsonb columns, so the baseline uses TEXT.
        // Guard against a future edit flipping these to JSONB without the entity
        // modelling (@JdbcTypeCode(SqlTypes.JSON)) that would require.
        assertThat(normalize(extractTableBody("saga_instances"))).contains("payload TEXT");
        assertThat(normalize(extractTableBody("saga_steps"))).contains("payload TEXT");
        assertThat(normalize(extractTableBody("saga_steps"))).contains("compensation_payload TEXT");
        assertThat(extractTableBody("saga_instances")).doesNotContain("JSONB");
        assertThat(extractTableBody("saga_steps")).doesNotContain("JSONB");
    }

    @Test
    void keepsOutboxClaimIndexes() {
        // The claim query is WHERE status=.. ORDER BY created_at .. FOR UPDATE
        // SKIP LOCKED; the leading (status, created_at) index is what keeps it
        // fast at high sweep concurrency.
        assertThat(sql).contains("idx_outbox_status_created");
        assertThat(sql).contains("(status, created_at)");
        assertThat(sql).contains("idx_outbox_aggregate");
    }

    @Test
    void keepsIdempotencyUniqueGuardAndExpiryIndex() {
        assertThat(sql).contains("uk_idempotency_scope_key");
        assertThat(sql).contains("UNIQUE (scope, idempotency_key)");
        assertThat(sql).contains("idx_idempotency_expires");
    }

    @Test
    void keepsSagaUniquenessAndCascade() {
        assertThat(sql).contains("uq_saga_instances_saga_id");
        assertThat(sql).contains("ON DELETE CASCADE");
        assertThat(sql).contains("uq_saga_instance_step");
    }

    @Test
    void everyTableHasNotNullCreatedAt() {
        for (String table : List.of("outbox_events", "dead_letter_events",
                "saga_instances", "saga_steps", "idempotency_records")) {
            assertThat(normalize(extractTableBody(table)))
                    .as("created_at of %s", table)
                    .contains("created_at TIMESTAMP(6) NOT NULL");
        }
    }

    /** Extracts the text between this table's CREATE TABLE and the next one. */
    private static String extractTableBody(String table) {
        int start = sql.indexOf("CREATE TABLE " + table);
        int end = sql.indexOf("CREATE TABLE ", start + 1);
        return sql.substring(start, end == -1 ? sql.length() : end);
    }

    /** Collapses runs of whitespace so aligned column DDL matches single-space assertions. */
    private static String normalize(String input) {
        return input.replaceAll("\\s+", " ").trim();
    }

    /** Removes {@code --} comment lines so the header's mapping docs don't trip construct checks. */
    private static String stripComments(String input) {
        return input.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static long countOccurrences(String haystack, String needle) {
        return Arrays.stream(haystack.split(needle, -1)).count() - 1;
    }
}
