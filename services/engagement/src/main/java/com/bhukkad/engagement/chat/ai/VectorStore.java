package com.bhukkad.engagement.chat.ai;

import java.util.List;
import java.util.Optional;

/**
 * Vector store abstraction for storing and retrieving document embeddings.
 * Implementations can be backed by pgvector (Postgres), in-memory, or other vector databases.
 */
public interface VectorStore {

    /**
     * Adds a document with its embedding to the store.
     *
     * @param documentId unique identifier for the document
     * @param content    the text content of the document
     * @param embedding  the embedding vector (typically 768, 1536, or 3072 dimensions)
     */
    void add(String documentId, String content, float[] embedding);

    /**
     * Searches for the most similar documents to the given query embedding.
     *
     * @param queryEmbedding the query embedding vector
     * @param topK           maximum number of results to return
     * @return list of (documentId, content, similarityScore) tuples, sorted by similarity descending
     */
    List<SearchResult> search(float[] queryEmbedding, int topK);

    /**
     * Deletes a document from the store.
     *
     * @param documentId the document identifier to delete
     * @return true if the document was found and deleted, false otherwise
     */
    boolean delete(String documentId);

    /**
     * Gets the total number of documents in the store.
     *
     * @return document count
     */
    long size();

    /**
     * Clears all documents from the store.
     */
    void clear();

    /**
     * Result of a vector similarity search.
     */
    record SearchResult(String documentId, String content, float score) {}
}