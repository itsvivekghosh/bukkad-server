package com.bhukkad.engagement.chat.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory vector store implementation.
 * <p>
 * <b>DEV ONLY</b> — Not persistent, not thread-safe for concurrent writes without external synchronization,
 * not suitable for production use. All data is lost on restart.
 * <p>
 * Activated by default when no pgvector-backed store is available. Use {@code @Profile("dev")} or
 * explicit configuration to limit to development environments.
 */
@Component
@Profile({"dev", "test", "local"})
public class InMemoryVectorStore implements VectorStore {

    private final Map<String, Document> store = new ConcurrentHashMap<>();
    private final AtomicLong operationCount = new AtomicLong(0);

    @Override
    public void add(String documentId, String content, float[] embedding) {
        if (documentId == null || content == null || embedding == null) {
            throw new IllegalArgumentException("documentId, content, and embedding must not be null");
        }
        store.put(documentId, new Document(documentId, content, embedding.clone()));
        operationCount.incrementAndGet();
    }

    @Override
    public List<SearchResult> search(float[] queryEmbedding, int topK) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            throw new IllegalArgumentException("queryEmbedding must not be null or empty");
        }
        if (topK <= 0) {
            return List.of();
        }

        // Compute cosine similarity for all documents
        List<SearchResult> results = store.values().stream()
                .map(doc -> new SearchResult(
                        doc.documentId(),
                        doc.content(),
                        cosineSimilarity(queryEmbedding, doc.embedding())))
                .filter(r -> !Float.isNaN(r.score()))
                .sorted((a, b) -> Float.compare(b.score(), a.score()))
                .limit(topK)
                .toList();

        operationCount.incrementAndGet();
        return results;
    }

    @Override
    public boolean delete(String documentId) {
        boolean removed = store.remove(documentId) != null;
        operationCount.incrementAndGet();
        return removed;
    }

    @Override
    public long size() {
        return store.size();
    }

    @Override
    public void clear() {
        store.clear();
        operationCount.set(0);
    }

    /**
     * Computes cosine similarity between two vectors.
     * Returns NaN if vectors have different lengths or zero magnitude.
     */
    private static float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return Float.NaN;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return Float.NaN;
        }
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    /**
     * Internal document representation.
     */
    private record Document(String documentId, String content, float[] embedding) {}

    /**
     * Returns the total number of operations performed (for testing/observability).
     */
    public long getOperationCount() {
        return operationCount.get();
    }
}