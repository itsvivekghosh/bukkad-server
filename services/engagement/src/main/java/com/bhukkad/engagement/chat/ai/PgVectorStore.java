package com.bhukkad.engagement.chat.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL pgvector-backed vector store.
 * <p>
 * Requires the pgvector extension to be installed in the target PostgreSQL database:
 * <pre>
 * CREATE EXTENSION IF NOT EXISTS vector;
 * </pre>
 * And a table created with:
 * <pre>
 * CREATE TABLE chat_vector_store (
 *     document_id VARCHAR(255) PRIMARY KEY,
 *     content TEXT NOT NULL,
 *     embedding vector(1536) NOT NULL
 * );
 * CREATE INDEX ON chat_vector_store USING hnsw (embedding vector_cosine_ops);
 * </pre>
 * <p>
 * Enabled via property {@code app.chatbot.vectorstore=pgvector}. When disabled or pgvector
 * extension is not available, falls back to {@link InMemoryVectorStore}.
 */
@Component
@ConditionalOnProperty(name = "app.chatbot.vectorstore", havingValue = "pgvector", matchIfMissing = false)
public class PgVectorStore implements VectorStore {

    private static final String TABLE_NAME = "chat_vector_store";
    private static final int EMBEDDING_DIMENSIONS = 1536;

    private final JdbcTemplate jdbcTemplate;

    public PgVectorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureTableExists();
    }

    private void ensureTableExists() {
        try {
            // Check if table exists
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                    Integer.class, TABLE_NAME);
            if (count == null || count == 0) {
                createTable();
            }
        } catch (Exception e) {
            // Table might not exist, try to create
            createTable();
        }
    }

    private void createTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS %s (
                document_id VARCHAR(255) PRIMARY KEY,
                content TEXT NOT NULL,
                embedding vector(%d) NOT NULL
            )
            """.formatted(TABLE_NAME, EMBEDDING_DIMENSIONS);
        jdbcTemplate.execute(sql);

        // Create HNSW index for fast approximate nearest neighbor search
        String indexSql = """
            CREATE INDEX IF NOT EXISTS idx_%s_embedding_hnsw
            ON %s USING hnsw (embedding vector_cosine_ops)
            """.formatted(TABLE_NAME, TABLE_NAME);
        try {
            jdbcTemplate.execute(indexSql);
        } catch (Exception e) {
            // HNSW index may not be supported on older pgvector versions; fall back to ivfflat
            String fallbackIndex = """
                CREATE INDEX IF NOT EXISTS idx_%s_embedding_ivfflat
                ON %s USING ivfflat (embedding vector_cosine_ops)
                WITH (lists = 100)
                """.formatted(TABLE_NAME, TABLE_NAME);
            try {
                jdbcTemplate.execute(fallbackIndex);
            } catch (Exception ex) {
                // Log but continue - index is optional for functionality
            }
        }
    }

    @Override
    public void add(String documentId, String content, float[] embedding) {
        if (embedding.length != EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException("Embedding must have %d dimensions, got %d".formatted(EMBEDDING_DIMENSIONS, embedding.length));
        }

        String vectorLiteral = vectorToLiteral(embedding);
        String sql = "INSERT INTO %s (document_id, content, embedding) VALUES (?, ?, %s::vector) " +
                     "ON CONFLICT (document_id) DO UPDATE SET content = EXCLUDED.content, embedding = EXCLUDED.embedding"
                     .formatted(TABLE_NAME, vectorLiteral);

        jdbcTemplate.update(sql, documentId, content);
    }

    @Override
    public List<SearchResult> search(float[] queryEmbedding, int topK) {
        if (queryEmbedding.length != EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException("Query embedding must have %d dimensions, got %d".formatted(EMBEDDING_DIMENSIONS, queryEmbedding.length));
        }
        if (topK <= 0) {
            return List.of();
        }

        String vectorLiteral = vectorToLiteral(queryEmbedding);
        String sql = """
            SELECT document_id, content, 1 - (embedding <=> %s::vector) AS similarity
            FROM %s
            ORDER BY embedding <=> %s::vector
            LIMIT ?
            """.formatted(vectorLiteral, TABLE_NAME, vectorLiteral);

        return jdbcTemplate.query(sql, new VectorSearchRowMapper(), topK);
    }

    @Override
    public boolean delete(String documentId) {
        String sql = "DELETE FROM %s WHERE document_id = ?".formatted(TABLE_NAME);
        int rows = jdbcTemplate.update(sql, documentId);
        return rows > 0;
    }

    @Override
    public long size() {
        String sql = "SELECT COUNT(*) FROM %s".formatted(TABLE_NAME);
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public void clear() {
        String sql = "DELETE FROM %s".formatted(TABLE_NAME);
        jdbcTemplate.update(sql);
    }

    /**
     * Converts a float array to a PostgreSQL vector literal string: '[0.1,0.2,0.3]'
     */
    private static String vectorToLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static class VectorSearchRowMapper implements RowMapper<SearchResult> {
        @Override
        public SearchResult mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SearchResult(
                    rs.getString("document_id"),
                    rs.getString("content"),
                    rs.getFloat("similarity")
            );
        }
    }
}