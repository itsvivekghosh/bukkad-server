package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.*;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Kinesis service for high-throughput event streaming.
 * <p>
 * Provides batched, asynchronous record publishing for order events,
 * clickstream data, and real-time analytics.
 */
@Service
public class KinesisService {

    private final KinesisClient kinesisClient;
    private final AwsProperties awsProperties;

    public KinesisService(KinesisClient kinesisClient, AwsProperties awsProperties) {
        this.kinesisClient = kinesisClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates a Kinesis stream for order events.
     * Should be called during infrastructure setup.
     */
    public void createOrderEventStream(int shardCount) {
        String streamName = awsProperties.getKinesisStreamPrefix() + "order-events";

        try {
            kinesisClient.createStream(CreateStreamRequest.builder()
                    .streamName(streamName)
                    .streamModeDetails(StreamModeDetails.builder()
                            .streamMode(StreamMode.ON_DEMAND)
                            .build())
                    .build());
        } catch (ResourceInUseException e) {
            // Stream already exists, update shard count if needed
            updateShardCount(streamName, shardCount);
        }
    }

    /**
     * Updates the shard count for a stream.
     */
    public void updateShardCount(String streamName, int targetShardCount) {
        kinesisClient.updateShardCount(UpdateShardCountRequest.builder()
                .streamName(streamName)
                .targetShardCount(targetShardCount)
                .scalingType(ScalingType.UNIFORM_SCALING)
                .build());
    }

    /**
     * Publishes a single order event to Kinesis.
     */
    public CompletableFuture<PutRecordResponse> publishOrderEvent(String orderId, String eventType,
                                                                   Map<String, Object> eventData) {
        String streamName = awsProperties.getKinesisStreamPrefix() + "order-events";

        String partitionKey = orderId;
        String data = buildEventJson(orderId, eventType, eventData);

        PutRecordRequest request = PutRecordRequest.builder()
                .streamName(streamName)
                .partitionKey(partitionKey)
                .data(SdkBytes.fromUtf8String(data))
                .build();

        return CompletableFuture.supplyAsync(() -> kinesisClient.putRecord(request));
    }

    /**
     * Publishes multiple order events in a batch (up to 500 records per request).
     * More efficient for high-throughput scenarios.
     */
    public CompletableFuture<PutRecordsResponse> publishOrderEventsBatch(List<OrderEvent> events) {
        String streamName = awsProperties.getKinesisStreamPrefix() + "order-events";

        List<PutRecordsRequestEntry> entries = events.stream()
                .map(event -> PutRecordsRequestEntry.builder()
                        .partitionKey(event.orderId())
                        .data(SdkBytes.fromUtf8String(buildEventJson(event.orderId(), event.eventType(), event.data())))
                        .build())
                .collect(Collectors.toList());

        // Split into batches of 500 (Kinesis limit)
        return publishInBatches(streamName, entries);
    }

    private CompletableFuture<PutRecordsResponse> publishInBatches(String streamName, List<PutRecordsRequestEntry> entries) {
        if (entries.size() <= 500) {
            PutRecordsRequest request = PutRecordsRequest.builder()
                    .streamName(streamName)
                    .records(entries)
                    .build();
            return CompletableFuture.supplyAsync(() -> kinesisClient.putRecords(request));
        }

        // For larger batches, split and publish concurrently
        List<List<PutRecordsRequestEntry>> batches = splitIntoBatches(entries, 500);
        List<CompletableFuture<PutRecordsResponse>> futures = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(() -> {
                    PutRecordsRequest request = PutRecordsRequest.builder()
                            .streamName(streamName)
                            .records(batch)
                            .build();
                    return kinesisClient.putRecords(request);
                }))
                .toList();

        // Combine results
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    PutRecordsResponse combined = PutRecordsResponse.builder()
                            .failedRecordCount(futures.stream()
                                    .mapToInt(f -> f.join().failedRecordCount())
                                    .sum())
                            .records(futures.stream()
                                    .flatMap(f -> f.join().records().stream())
                                    .toList())
                            .build();
                    return combined;
                });
    }

    private List<List<PutRecordsRequestEntry>> splitIntoBatches(List<PutRecordsRequestEntry> entries, int batchSize) {
        return java.util.stream.IntStream.range(0, (entries.size() + batchSize - 1) / batchSize)
                .mapToObj(i -> entries.subList(i * batchSize, Math.min((i + 1) * batchSize, entries.size())))
                .toList();
    }

    /**
     * Gets stream description for monitoring.
     */
    public DescribeStreamResponse describeStream(String streamName) {
        return kinesisClient.describeStream(DescribeStreamRequest.builder()
                .streamName(streamName)
                .limit(100)
                .build());
    }

    /**
     * Enables enhanced monitoring for shard-level metrics.
     */
    public void enableEnhancedMonitoring(String streamName) {
        kinesisClient.enableEnhancedMonitoring(EnableEnhancedMonitoringRequest.builder()
                .streamName(streamName)
                .shardLevelMetrics(MetricsName.ALL)
                .build());
    }

    /**
     * Starts a stream consumer for enhanced fan-out (dedicated throughput).
     */
    public String registerStreamConsumer(String streamName, String consumerName) {
        return kinesisClient.registerStreamConsumer(RegisterStreamConsumerRequest.builder()
                .streamARN("arn:aws:kinesis:" + awsProperties.getRegion() + ":" + awsProperties.getAccountId() + ":stream/" + streamName)
                .consumerName(consumerName)
                .build())
                .consumer().consumerARN();
    }

    /**
     * Gets records from a shard iterator (for consumer applications).
     */
    public GetRecordsResponse getRecords(String shardIterator) {
        return kinesisClient.getRecords(GetRecordsRequest.builder()
                .shardIterator(shardIterator)
                .limit(10000)
                .build());
    }

    /**
     * Gets a shard iterator for reading from a specific position.
     */
    public String getShardIterator(String streamName, String shardId, ShardIteratorType iteratorType) {
        return kinesisClient.getShardIterator(GetShardIteratorRequest.builder()
                .streamName(streamName)
                .shardId(shardId)
                .shardIteratorType(iteratorType)
                .build()).shardIterator();
    }

    private String buildEventJson(String orderId, String eventType, Map<String, Object> eventData) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var event = Map.<String, Object>of(
                    "eventId", UUID.randomUUID().toString(),
                    "orderId", orderId,
                    "eventType", eventType,
                    "timestamp", Instant.now().toString(),
                    "data", eventData
            );
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event", e);
        }
    }

    public record OrderEvent(String orderId, String eventType, Map<String, Object> data) {}
}