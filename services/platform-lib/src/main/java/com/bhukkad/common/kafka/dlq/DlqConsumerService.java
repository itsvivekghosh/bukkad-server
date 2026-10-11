package com.bhukkad.common.kafka.dlq;

import com.bhukkad.common.kafka.KafkaPlatformProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for consuming and inspecting DLQ events.
 *
 * <p>Provides a non-blocking poll-based API to fetch dead-letter events
 * without interfering with the main event processing pipeline.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DlqConsumerService {

    private final ObjectMapper objectMapper;
    private final KafkaPlatformProperties properties;
    private final KafkaConsumer<String, String> dlqConsumer;

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

    /**
     * Fetches up to {@code maxRecords} dead-letter events.
     *
     * @param maxRecords maximum number of records to return (1-1000)
     * @return list of DLQ events with metadata
     */
    public List<DlqEvent> fetchDeadLetters(int maxRecords) {
        if (maxRecords <= 0 || maxRecords > 1000) {
            maxRecords = 100;
        }
        
        List<DlqEvent> events = new ArrayList<>();
        
        try {
            var records = dlqConsumer.poll(Duration.ofMillis(1000));
            int count = 0;
            
            for (ConsumerRecord<String, String> record : records) {
                if (count >= maxRecords) {
                    break;
                }
                
                try {
                    JsonNode payload = objectMapper.readTree(record.value());
                    
                    DlqEvent event = convertRecord(record);
                    events.add(event);
                    count++;
                } catch (Exception e) {
                    log.warn("Failed to parse DLQ record at offset {}: {}", record.offset(), e.getMessage());
                }
            }
            
            // Commit offsets for records we processed
            if (!records.isEmpty()) {
                dlqConsumer.commitSync();
            }
            
        } catch (Exception e) {
            log.error("Error polling DLQ: {}", e.getMessage());
        }
        
        return events;
    }
    
    /**
     * Converts a ConsumerRecord to a DlqEvent.
     */
    public DlqEvent convertRecord(ConsumerRecord<String, String> record) {
        try {
            JsonNode payload = objectMapper.readTree(record.value());
            
            DlqEvent event = new DlqEvent();
            event.setTopic(record.topic());
            event.setPartition(record.partition());
            event.setOffset(record.offset());
            event.setTimestamp(record.timestamp());
            event.setTimestampType(record.timestampType().name());
            event.setKey(record.key());
            event.setValue(payload);
            event.setHeaders(record.headers().toArray());
            event.setFailedTopic(record.headers().lastHeader("x-failed-topic") != null 
                    ? new String(record.headers().lastHeader("x-failed-topic").value()) : null);
            event.setErrorClass(record.headers().lastHeader("x-error-class") != null
                    ? new String(record.headers().lastHeader("x-error-class").value()) : null);
            return event;
        } catch (Exception e) {
            log.warn("Failed to convert record at offset {}: {}", record.offset(), e.getMessage());
            return null;
        }
    }
    
    /**
     * Exposes the underlying Kafka consumer for advanced operations.
     */
    public KafkaConsumer<String, String> getDlqConsumer() {
        return dlqConsumer;
    }

    /**
     * Gets the count of pending DLQ messages (approximate).
     */
    public long getPendingCount() {
        try {
            var partitions = dlqConsumer.assignment();
            if (partitions.isEmpty()) {
                return 0L;
            }
            
            dlqConsumer.poll(Duration.ZERO);
            var endOffsets = dlqConsumer.endOffsets(partitions);
            var currentOffsets = new ConcurrentHashMap<org.apache.kafka.common.TopicPartition, Long>();
            
            for (var partition : partitions) {
                currentOffsets.put(partition, dlqConsumer.position(partition));
            }
            
            long total = 0;
            for (var partition : partitions) {
                Long end = endOffsets.get(partition);
                Long current = currentOffsets.get(partition);
                if (end != null && current != null && end > current) {
                    total += (end - current);
                }
            }
            return total;
        } catch (Exception e) {
            log.warn("Could not get DLQ pending count: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Data class for DLQ event representation.
     */
    public static class DlqEvent {
        private String topic;
        private int partition;
        private long offset;
        private long timestamp;
        private String timestampType;
        private String key;
        private JsonNode value;
        private org.apache.kafka.common.header.Header[] headers;
        private String failedTopic;
        private String errorClass;
        private Instant fetchedAt = Instant.now();

        // Getters and setters
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public int getPartition() { return partition; }
        public void setPartition(int partition) { this.partition = partition; }
        public long getOffset() { return offset; }
        public void setOffset(long offset) { this.offset = offset; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public String getTimestampType() { return timestampType; }
        public void setTimestampType(String timestampType) { this.timestampType = timestampType; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public JsonNode getValue() { return value; }
        public void setValue(JsonNode value) { this.value = value; }
        public org.apache.kafka.common.header.Header[] getHeaders() { return headers; }
        public void setHeaders(org.apache.kafka.common.header.Header[] headers) { this.headers = headers; }
        public String getFailedTopic() { return failedTopic; }
        public void setFailedTopic(String failedTopic) { this.failedTopic = failedTopic; }
        public String getErrorClass() { return errorClass; }
        public void setErrorClass(String errorClass) { this.errorClass = errorClass; }
        public Instant getFetchedAt() { return fetchedAt; }
        public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
    }
}