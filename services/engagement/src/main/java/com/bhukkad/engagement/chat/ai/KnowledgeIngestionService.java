package com.bhukkad.engagement.chat.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scheduled service that indexes knowledge base content (help center, policies, FAQ)
 * into the vector store as embeddings for RAG retrieval.
 * <p>
 * Guarded by feature flag {@code app.chatbot.enabled} (default: false).
 * Runs periodically to keep the vector store in sync with source content.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableScheduling
@ConditionalOnProperty(name = "app.chatbot.enabled", havingValue = "true", matchIfMissing = false)
public class KnowledgeIngestionService {

    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    private final KnowledgeSourceProvider knowledgeSourceProvider;

    /**
     * Runs the full knowledge ingestion pipeline.
     * Scheduled to run daily at 3 AM by default (configurable via {@code app.chatbot.ingestion.cron}).
     */
    @Scheduled(cron = "${app.chatbot.ingestion.cron:0 0 3 * * *}")
    @Transactional
    public void ingestKnowledgeBase() {
        log.info("KNOWLEDGE_INGESTION_STARTED | timestamp={}", LocalDateTime.now());

        try {
            List<KnowledgeDocument> documents = knowledgeSourceProvider.fetchAllDocuments();
            log.info("KNOWLEDGE_INGESTION_FETCHED | count={}", documents.size());

            int ingested = 0;
            int failed = 0;

            for (KnowledgeDocument doc : documents) {
                try {
                    float[] embedding = embeddingProvider.embed(doc.content());
                    vectorStore.add(doc.id(), doc.content(), embedding);
                    ingested++;
                } catch (Exception e) {
                    failed++;
                    log.warn("KNOWLEDGE_INGESTION_DOC_FAILED | id={} | error={}", doc.id(), e.getMessage());
                }
            }

            log.info("KNOWLEDGE_INGESTION_COMPLETED | ingested={} | failed={} | storeSize={}",
                    ingested, failed, vectorStore.size());

        } catch (Exception e) {
            log.error("KNOWLEDGE_INGESTION_FAILED | error={}", e.getMessage(), e);
        }
    }

    /**
     * Triggers an immediate ingestion (e.g., via admin endpoint).
     */
    @Transactional
    public IngestionResult triggerIngestion() {
        log.info("KNOWLEDGE_INGESTION_TRIGGERED_MANUALLY");
        long startSize = vectorStore.size();
        long startTime = System.currentTimeMillis();

        try {
            List<KnowledgeDocument> documents = knowledgeSourceProvider.fetchAllDocuments();
            int ingested = 0;
            int failed = 0;

            for (KnowledgeDocument doc : documents) {
                try {
                    float[] embedding = embeddingProvider.embed(doc.content());
                    vectorStore.add(doc.id(), doc.content(), embedding);
                    ingested++;
                } catch (Exception e) {
                    failed++;
                    log.warn("KNOWLEDGE_INGESTION_DOC_FAILED | id={} | error={}", doc.id(), e.getMessage());
                }
            }

            long durationMs = System.currentTimeMillis() - startTime;
            return new IngestionResult(true, ingested, failed, vectorStore.size() - startSize, durationMs);

        } catch (Exception e) {
            log.error("KNOWLEDGE_INGESTION_MANUAL_FAILED | error={}", e.getMessage(), e);
            return new IngestionResult(false, 0, 0, 0, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Result of a knowledge ingestion run.
     */
    public record IngestionResult(
            boolean success,
            int documentsIngested,
            int documentsFailed,
            int netStoreSizeChange,
            long durationMs
    ) {}
}