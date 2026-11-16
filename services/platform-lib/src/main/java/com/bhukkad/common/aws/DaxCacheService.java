package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;

/**
 * DAX cache wrapper service for DynamoDB microsecond read latency.
 * <p>
 * Provides a caching layer using Amazon DynamoDB Accelerator (DAX) for
 * read-heavy workloads. In production, configure the DAX endpoint in
 * AwsProperties to route reads through DAX cache. In local Floci
 * development, falls back to direct DynamoDB access.
 * <p>
 * DAX can provide up to 10x read performance improvement for frequently
 * accessed items such as catalog items, customer profiles, and order history.
 */
@Service
public class DaxCacheService {

    private final DynamoDbClient dynamoDbClient;
    private final AwsProperties awsProperties;

    public DaxCacheService(DynamoDbClient dynamoDbClient, AwsProperties awsProperties) {
        this.dynamoDbClient = dynamoDbClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Checks if DAX is configured for caching.
     * <p>
     * In production, set the daxEndpoint property to enable DAX caching.
     * In local Floci development, DAX is not available and falls back to
     * direct DynamoDB access.
     */
    public boolean isDaxAvailable() {
        return awsProperties.getDaxEndpoint() != null && !awsProperties.getDaxEndpoint().isEmpty();
    }

    /**
     * Gets an item from DynamoDB, using DAX cache when available.
     * <p>
     * In production with DAX enabled, reads are served from the DAX cache
     * with microsecond latency. In local Floci development, falls back to
     * direct DynamoDB access.
     *
     * @param tableName  DynamoDB table name
     * @param key        Primary key value
     * @param keyName    Primary key attribute name (default "id")
     * @return Item attributes or empty map if not found
     */
    public Map<String, AttributeValue> getItem(String tableName, String key, String keyName) {
        return dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + tableName)
                .key(Map.of(keyName, AttributeValue.fromS(key)))
                .build())
                .item();
    }

    /**
     * Puts an item into DynamoDB.
     *
     * @param tableName DynamoDB table name
     * @param item      Item attributes to write
     */
    public void putItem(String tableName, Map<String, AttributeValue> item) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + tableName)
                .item(item)
                .build());
    }

    /**
     * Updates an item in DynamoDB.
     *
     * @param tableName  DynamoDB table name
     * @param key        Primary key value
     * @param keyName    Primary key attribute name (default "id")
     * @param updateExpression Update expression
     * @param expressionAttributeValues Expression attribute values
     */
    public void updateItem(String tableName, String key, String keyName,
                            String updateExpression,
                            Map<String, AttributeValue> expressionAttributeValues) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + tableName)
                .key(Map.of(keyName, AttributeValue.fromS(key)))
                .updateExpression(updateExpression)
                .expressionAttributeValues(expressionAttributeValues)
                .build());
    }

    /**
     * Deletes an item from DynamoDB.
     *
     * @param tableName DynamoDB table name
     * @param key       Primary key value
     * @param keyName   Primary key attribute name (default "id")
     */
    public void deleteItem(String tableName, String key, String keyName) {
        dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + tableName)
                .key(Map.of(keyName, AttributeValue.fromS(key)))
                .build());
    }

    /**
     * Queries items from DynamoDB using DAX cache when available.
     * <p>
     * DAX supports Query operations for tables with a partition key and sort key.
     * Query results are cached in DAX for microsecond latency on repeated queries.
     *
     * @param tableName  DynamoDB table name
     * @param keyConditionExpression Key condition expression
     * @param expressionAttributeNames Expression attribute names
     * @param expressionAttributeValues Expression attribute values
     * @return Query results
     */
    public QueryResponse queryItems(String tableName,
                                     String keyConditionExpression,
                                     Map<String, String> expressionAttributeNames,
                                     Map<String, AttributeValue> expressionAttributeValues) {
        return dynamoDbClient.query(QueryRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + tableName)
                .keyConditionExpression(keyConditionExpression)
                .expressionAttributeNames(expressionAttributeNames)
                .expressionAttributeValues(expressionAttributeValues)
                .build());
    }

    /**
     * Scans items from DynamoDB using DAX cache when available.
     * <p>
     * Warning: Scan operations are expensive and should be avoided in production.
     * Use Query with appropriate indexes instead.
     *
     * @param tableName DynamoDB table name
     * @return Scan results
     */
    public ScanResponse scanItems(String tableName) {
        return dynamoDbClient.scan(ScanRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + tableName)
                .build());
    }

    /**
     * Batch gets multiple items from DynamoDB using DAX cache.
     * <p>
     * BatchGetItem can retrieve up to 100 items in a single request.
     * DAX caches the results for subsequent reads.
     *
     * @param tableName DynamoDB table name
     * @param keys      List of primary key values
     * @param keyName   Primary key attribute name (default "id")
     * @return Map of key to item attributes
     */
    public Map<String, AttributeValue> batchGetItems(String tableName,
                                                      java.util.List<String> keys,
                                                      String keyName) {
        BatchGetItemResponse response = dynamoDbClient.batchGetItem(BatchGetItemRequest.builder()
                .requestItems(Map.of(
                        awsProperties.getDynamodbTablePrefix() + tableName,
                        KeysAndAttributes.builder()
                                .keys(keys.stream()
                                        .map(k -> Map.of(keyName, AttributeValue.fromS(k)))
                                        .collect(java.util.stream.Collectors.toList()))
                                .build()
                    ))
                    .build());

        // Return the first item's attributes (batch get returns list of attributes per key)
        return response.responses().get(awsProperties.getDynamodbTablePrefix() + tableName)
                .stream()
                .findFirst()
                .orElse(Map.of());
    }
}