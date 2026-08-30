package com.bhukkad.integration;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the squashed {@code V1__baseline_schema.sql} is a faithful
 * consolidation of the pre-squash migration series.
 *
 * <p>Strategy: apply the LEGACY migration set (snapshot of every file that
 * existed before the squash, stored in
 * {@code test/resources/db/migration-legacy}) to one fresh MySQL database,
 * apply the CURRENT migration set (squashed V1 + incremental V62/V63) to a
 * second fresh database, then diff {@code information_schema} — tables,
 * columns (name, type, nullability, defaults) and indexes (name, columns,
 * uniqueness).</p>
 *
 * <p>This is the "test it thoroughly" gate for the squash: any statement
 * lost, duplicated or reordered incorrectly during the consolidation shows
 * up as a schema diff instead of a silent production divergence. Requires
 * Docker; skips cleanly without it (runs in CI).</p>
 */
class SchemaEquivalenceIntegrationTest {

    private static final DockerImageName MYSQL_IMAGE =
            DockerImageName.parse("mysql:8.0").asCompatibleSubstituteFor("mysql");

    private static final String LEGACY_LOCATION = "classpath:db/migration-legacy";
    private static final String CURRENT_LOCATION = "classpath:db/migration";

    @Test
    void squashedBaselineProducesIdenticalSchemaToLegacySequence() throws Exception {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new TestAbortedException("Docker not available; schema equivalence test requires MySQL via Testcontainers");
        }

        try (MySQLContainer<?> legacyDb = new MySQLContainer<>(MYSQL_IMAGE)
                .withDatabaseName("legacy_db").withUsername("legacy").withPassword("legacy_pw");
             MySQLContainer<?> squashedDb = new MySQLContainer<>(MYSQL_IMAGE)
                .withDatabaseName("squashed_db").withUsername("squash").withPassword("squash_pw")) {

            legacyDb.start();
            squashedDb.start();

            migrateWithFlyway(legacyDb, LEGACY_LOCATION);
            migrateWithFlyway(squashedDb, CURRENT_LOCATION);

            Map<String, TableSchema> legacySchema = dumpSchema(legacyDb);
            Map<String, TableSchema> squashedSchema = dumpSchema(squashedDb);

            Set<String> legacyTables = new HashSet<>(legacySchema.keySet());
            Set<String> squashedTables = new HashSet<>(squashedSchema.keySet());

            // Flyway's own history table legitimately differs (different
            // migration sets, different checksums) — exclude it from the diff.
            legacyTables.remove("flyway_schema_history");
            squashedTables.remove("flyway_schema_history");

            assertThat(squashedTables)
                    .as("tables missing from squashed baseline")
                    .containsExactlyInAnyOrderElementsOf(legacyTables);

            for (String table : legacyTables) {
                TableSchema legacy = legacySchema.get(table);
                TableSchema squashed = squashedSchema.get(table);
                assertThat(squashed.columns.keySet())
                        .as("columns of table %s", table)
                        .containsExactlyInAnyOrderElementsOf(legacy.columns.keySet());
                for (Map.Entry<String, ColumnDef> col : legacy.columns.entrySet()) {
                    assertThat(squashed.columns.get(col.getKey()))
                            .as("column %s.%s", table, col.getKey())
                            .isEqualTo(col.getValue());
                }
                assertThat(squashed.indexes.keySet())
                        .as("indexes of table %s", table)
                        .containsExactlyInAnyOrderElementsOf(legacy.indexes.keySet());
                for (Map.Entry<String, IndexDef> idx : legacy.indexes.entrySet()) {
                    assertThat(squashed.indexes.get(idx.getKey()))
                            .as("index %s on %s", idx.getKey(), table)
                            .isEqualTo(idx.getValue());
                }
            }
        }
    }

    /** Applies the given Flyway location to the container's database. */
    private void migrateWithFlyway(MySQLContainer<?> db, String location) {
        org.flywaydb.core.Flyway.configure()
                .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .locations(location)
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    private Map<String, TableSchema> dumpSchema(MySQLContainer<?> db) throws Exception {
        Map<String, TableSchema> result = new HashMap<>();
        try (Connection conn = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
            String schema = db.getDatabaseName();

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT TABLE_NAME FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString(1), new TableSchema());
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT " +
                    "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME, ORDINAL_POSITION")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        TableSchema table = result.get(rs.getString(1));
                        if (table != null) {
                            table.columns.put(rs.getString(2), new ColumnDef(
                                    rs.getString(3), rs.getString(4), rs.getString(5)));
                        }
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, " +
                    "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols " +
                    "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? " +
                    "GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        TableSchema table = result.get(rs.getString(1));
                        if (table != null) {
                            table.indexes.put(rs.getString(2), new IndexDef(
                                    rs.getBoolean(3), rs.getString(4)));
                        }
                    }
                }
            }
        }
        return result;
    }

    /** Column definition snapshot (type, nullability, default). */
    private static final class ColumnDef {
        final String type;
        final String nullable;
        final String defaultValue;

        ColumnDef(String type, String nullable, String defaultValue) {
            this.type = type;
            this.nullable = nullable;
            this.defaultValue = defaultValue;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ColumnDef that)) return false;
            return type.equals(that.type)
                    && nullable.equals(that.nullable)
                    && java.util.Objects.equals(defaultValue, that.defaultValue);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(type, nullable, defaultValue);
        }
    }

    /** Index definition snapshot (uniqueness + ordered column list). */
    private static final class IndexDef {
        final boolean nonUnique;
        final String columns;

        IndexDef(boolean nonUnique, String columns) {
            this.nonUnique = nonUnique;
            this.columns = columns;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IndexDef that)) return false;
            return nonUnique == that.nonUnique && columns.equals(that.columns);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(nonUnique, columns);
        }
    }

    /** Per-table schema snapshot. */
    private static final class TableSchema {
        final Map<String, ColumnDef> columns = new HashMap<>();
        final Map<String, IndexDef> indexes = new HashMap<>();
    }
}
