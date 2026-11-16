package com.bhukkad.engagement.chat.ai;

import java.util.List;
import java.util.Map;

/**
 * Provider for knowledge base documents to be indexed into the vector store.
 * Implementations can fetch from CMS, database, files, or static configuration.
 */
public interface KnowledgeSourceProvider {

    /**
     * Fetches all knowledge documents for ingestion.
     *
     * @return list of knowledge documents
     */
    List<KnowledgeDocument> fetchAllDocuments();

    /**
     * Knowledge document with metadata.
     */
    record KnowledgeDocument(
            String id,
            String title,
            String content,
            String category,        // e.g., "help", "policy", "faq"
            String sourceUrl,       // optional reference URL
            Map<String, String> metadata
    ) {}
}