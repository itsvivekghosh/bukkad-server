package com.bhukkad.admin.kafka.dlq;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import com.bhukkad.common.kafka.KafkaPlatformProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for reprocessing (re-driving) dead-letter events.
 *
 * <p>Allows manual re-drive of failed events back to their original topic
 * or to a different topic for debugging.</p>
 *
 * <p>Both Kafka dependencies are optional so the service can be constructed
 * when the backbone is disabled: {@link #reprocessEvent} then reports the
 * problem instead of throwing a missing-bean error at context startup.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DlqReprocessService {

    private final ObjectMapper objectMapper;
    private final KafkaPlatformProperties properties;
    private final ObjectProvider<KafkaPlatformEventPublisher> publisherProvider;
    private final DlqConsumerService consumerService;

    // In-memory cache for reprocess tracking
    private final Map<String, ReprocessStatus> reprocessCache = new ConcurrentHashMap<>();

    /**
     * Reprocesses a single DLQ event by ID (offset).
     *
     * @param topic the DLQ topic
     * @param partition the partition
     * @param offset the offset of the record
     * @param targetTopic optional target topic (null = original topic from x-failed-topic header)
     * @return reprocess result
     */
    public ReprocessResult reprocessEvent(String topic, int partition, long offset, String targetTopic) {
        String cacheKey = topic + "-" + partition + "-" + offset;
        
        // Check if already reprocessed
        ReprocessStatus existing = reprocessCache.get(cacheKey);
        if (existing != null && existing.isSuccess()) {
            return new ReprocessResult(false, "Already reprocessed: " + existing.getMessage(), cacheKey);
        }
        
        // Fetch the specific record
        DlqConsumerService.DlqEvent event = fetchSpecificRecord(topic, partition, offset);
        if (event == null) {
            return new ReprocessResult(false, "Record not found at offset " + offset, cacheKey);
        }
        
        // Determine target topic
        String resolvedTarget = targetTopic != null ? targetTopic : event.getFailedTopic();
        if (resolvedTarget == null) {
            resolvedTarget = properties.kafka().platformTopic();
        }
        
        try {
            // Republish to target topic
            String eventType = event.getValue().has("eventType") 
                    ? event.getValue().get("eventType").asText() 
                    : "unknown";
            
            KafkaPlatformEventPublisher publisher = publisherProvider.getIfAvailable();
            if (publisher == null) {
                String message = "Kafka backbone is disabled; cannot reprocess DLQ events";
                log.warn("DLQ_REPROCESS_UNAVAILABLE | topic={} partition={} offset={}",
                        topic, partition, offset);
                return new ReprocessResult(false, message, cacheKey);
            }

            // Create a new event envelope for reprocessing
            Map<String, Object> envelope = Map.of(
                    "eventType", eventType,
                    "payload", event.getValue(),
                    "reprocessMetadata", Map.of(
                            "originalTopic", event.getFailedTopic(),
                            "originalOffset", offset,
                            "originalPartition", partition,
                            "reprocessedAt", java.time.Instant.now().toString(),
                            "reprocessReason", "manual_reprocess"
                    )
            );
            
            // Use the platform publisher
            publisher.publish(PlatformEventMessage.of(eventType, event.getFailedTopic(), objectMapper.writeValueAsString(envelope)));
            
            // Mark as reprocessed
            reprocessCache.put(cacheKey, new ReprocessStatus(true, 
                    "Successfully reprocessed to " + resolvedTarget, java.time.Instant.now()));
            
            log.info("DLQ_REPROCESS | topic={} partition={} offset={} -> {}", 
                    topic, partition, offset, resolvedTarget);
            
            return new ReprocessResult(true, "Successfully reprocessed to " + resolvedTarget, cacheKey);
            
        } catch (Exception e) {
            log.error("Failed to reprocess DLQ event: {}", e.getMessage());
            reprocessCache.put(cacheKey, new ReprocessStatus(false, e.getMessage(), java.time.Instant.now()));
            return new ReprocessResult(false, "Reprocess failed: " + e.getMessage(), cacheKey);
        }
    }
    
    /**
     * Batch reprocess multiple events.
     */
    public BatchReprocessResult reprocessBatch(List<ReprocessRequest> requests) {
        int success = 0;
        int failed = 0;
        var results = new java.util.ArrayList<ReprocessResult>();
        
        for (ReprocessRequest req : requests) {
            ReprocessResult result = reprocessEvent(
                    req.getTopic(), req.getPartition(), req.getOffset(), req.getTargetTopic());
            results.add(result);
            if (result.isSuccess()) {
                success++;
            } else {
                failed++;
            }
        }
        
        return new BatchReprocessResult(success, failed, results);
    }
    
    /**
     * Fetches a specific record by topic/partition/offset.
     */
    private DlqConsumerService.DlqEvent fetchSpecificRecord(String topic, int partition, long offset) {
        KafkaConsumer<String, String> consumer = consumerService.getDlqConsumer();
        if (consumer == null) {
            return null;
        }
        try {
            var partitions = java.util.Collections.singletonList(
                    new org.apache.kafka.common.TopicPartition(topic, partition));
            consumer.assign(partitions);
            consumer.seek(partitions.get(0), offset);

            var records = consumer.poll(java.time.Duration.ofMillis(500));
            for (var record : records) {
                if (record.offset() == offset) {
                    return consumerService.convertRecord(record);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch specific record: {}", e.getMessage());
        }
        return null;
    }

    // Request/Response DTOs
    public static class ReprocessRequest {
        private String topic;
        private int partition;
        private long offset;
        private String targetTopic;
        
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public int getPartition() { return partition; }
        public void setPartition(int partition) { this.partition = partition; }
        public long getOffset() { return offset; }
        public void setOffset(long offset) { this.offset = offset; }
        public String getTargetTopic() { return targetTopic; }
        public void setTargetTopic(String targetTopic) { this.targetTopic = targetTopic; }
    }
    
    public static class ReprocessResult {
        private boolean success;
        private String message;
        private String cacheKey;
        
        public ReprocessResult(boolean success, String message, String cacheKey) {
            this.success = success;
            this.message = message;
            this.cacheKey = cacheKey;
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getCacheKey() { return cacheKey; }
    }
    
    public static class BatchReprocessResult {
        private int success;
        private int failed;
        private List<ReprocessResult> results;
        
        public BatchReprocessResult(int success, int failed, List<ReprocessResult> results) {
            this.success = success;
            this.failed = failed;
            this.results = results;
        }
        
        public int getSuccess() { return success; }
        public int getFailed() { return failed; }
        public List<ReprocessResult> getResults() { return results; }
    }
    
    static class ReprocessStatus {
        private boolean success;
        private String message;
        private java.time.Instant timestamp;
        
        ReprocessStatus(boolean success, String message, java.time.Instant timestamp) {
            this.success = success;
            this.message = message;
            this.timestamp = timestamp;
        }
        
        boolean isSuccess() { return success; }
        String getMessage() { return message; }
    }
}