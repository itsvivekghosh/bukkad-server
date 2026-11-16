package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.List;
import java.util.Map;

/**
 * DynamoDB table management service with On-Demand billing support.
 * <p>
 * Provides table creation, configuration, and management with
 * automatic On-Demand (PAY_PER_REQUEST) billing for cost optimization.
 */
@Service
public class DynamoDbTableService {

    private final DynamoDbClient dynamoDbClient;
    private final AwsProperties awsProperties;

    public DynamoDbTableService(DynamoDbClient dynamoDbClient, AwsProperties awsProperties) {
        this.dynamoDbClient = dynamoDbClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates a table with On-Demand billing (PAY_PER_REQUEST).
     * <p>
     * On-Demand billing automatically scales capacity and charges per request,
     * eliminating the need for capacity planning.
     */
    public void createTableOnDemand(String tableName, List<KeySchemaElement> keySchema,
                                     List<AttributeDefinition> attributeDefinitions,
                                     List<GlobalSecondaryIndex> globalSecondaryIndexes) {
        BillingMode billingMode = BillingMode.fromValue(awsProperties.getDynamodbBillingMode());

        CreateTableRequest.Builder builder = CreateTableRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + tableName)
                .keySchema(keySchema)
                .attributeDefinitions(attributeDefinitions)
                .billingMode(billingMode);

        if (globalSecondaryIndexes != null && !globalSecondaryIndexes.isEmpty()) {
            builder.globalSecondaryIndexes(globalSecondaryIndexes);
        }

        dynamoDbClient.createTable(builder.build());
    }

    /**
     * Creates the standard Bhukkad tables with On-Demand billing.
     */
    public void createBhukkadTables() {
        // Orders table
        createTableOnDemand("orders",
                List.of(KeySchemaElement.builder().attributeName("orderId").keyType(KeyType.HASH).build()),
                List.of(AttributeDefinition.builder().attributeName("orderId").attributeType(ScalarAttributeType.S).build()),
                List.of(
                        GlobalSecondaryIndex.builder()
                                .indexName("customerId-index")
                                .keySchema(List.of(KeySchemaElement.builder().attributeName("customerId").keyType(KeyType.HASH).build()))
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build(),
                        GlobalSecondaryIndex.builder()
                                .indexName("restaurantId-index")
                                .keySchema(List.of(KeySchemaElement.builder().attributeName("restaurantId").keyType(KeyType.HASH).build()))
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build(),
                        GlobalSecondaryIndex.builder()
                                .indexName("status-createdAt-index")
                                .keySchema(List.of(
                                        KeySchemaElement.builder().attributeName("status").keyType(KeyType.HASH).build(),
                                        KeySchemaElement.builder().attributeName("createdAt").keyType(KeyType.RANGE).build()))
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build()
                ));

        // Customers table
        createTableOnDemand("customers",
                List.of(KeySchemaElement.builder().attributeName("customerId").keyType(KeyType.HASH).build()),
                List.of(AttributeDefinition.builder().attributeName("customerId").attributeType(ScalarAttributeType.S).build()),
                List.of(
                        GlobalSecondaryIndex.builder()
                                .indexName("email-index")
                                .keySchema(List.of(KeySchemaElement.builder().attributeName("email").keyType(KeyType.HASH).build()))
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build()
                ));

        // Menu items table
        createTableOnDemand("menu-items",
                List.of(KeySchemaElement.builder().attributeName("menuItemId").keyType(KeyType.HASH).build()),
                List.of(AttributeDefinition.builder().attributeName("menuItemId").attributeType(ScalarAttributeType.S).build()),
                List.of(
                        GlobalSecondaryIndex.builder()
                                .indexName("restaurantId-index")
                                .keySchema(List.of(KeySchemaElement.builder().attributeName("restaurantId").keyType(KeyType.HASH).build()))
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build(),
                        GlobalSecondaryIndex.builder()
                                .indexName("category-index")
                                .keySchema(List.of(KeySchemaElement.builder().attributeName("category").keyType(KeyType.HASH).build()))
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build()
                ));

        // Social posts table
        createTableOnDemand("social-posts",
                List.of(KeySchemaElement.builder().attributeName("postId").keyType(KeyType.HASH).build()),
                List.of(AttributeDefinition.builder().attributeName("postId").attributeType(ScalarAttributeType.S).build()),
                List.of(
                        GlobalSecondaryIndex.builder()
                                .indexName("customerId-index")
                                .keySchema(List.of(KeySchemaElement.builder().attributeName("customerId").keyType(KeyType.HASH).build()))
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build(),
                        GlobalSecondaryIndex.builder()
                                .indexName("createdAt-index")
                                .keySchema(List.of(KeySchemaElement.builder().attributeName("createdAt").keyType(KeyType.HASH).build()))
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build()
                ));

        // Tickets table
        createTableOnDemand("tickets",
                List.of(KeySchemaElement.builder().attributeName("ticketId").keyType(KeyType.HASH).build()),
                List.of(AttributeDefinition.builder().attributeName("ticketId").attributeType(ScalarAttributeType.S).build()),
                List.of(
                        GlobalSecondaryIndex.builder()
                                .indexName("customerId-index")
                                .keySchema(List.of(KeySchemaElement.builder().attributeName("customerId").keyType(KeyType.HASH).build()))
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build(),
                        GlobalSecondaryIndex.builder()
                                .indexName("status-index")
                                .keySchema(List.of(KeySchemaElement.builder().attributeName("status").keyType(KeyType.HASH).build()))
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build()
                ));
    }

    /**
     * Enables TTL (Time To Live) on a table attribute.
     * <p>
     * Automatically expires items after the specified timestamp attribute.
     */
    public void enableTtl(String tableName, String ttlAttributeName) {
        dynamoDbClient.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + tableName)
                .timeToLiveSpecification(TimeToLiveSpecification.builder()
                        .attributeName(ttlAttributeName)
                        .enabled(true)
                        .build())
                .build());
    }

    /**
     * Enables Point-in-Time Recovery (PITR) for disaster recovery.
     */
    public void enablePointInTimeRecovery(String tableName) {
        dynamoDbClient.updateContinuousBackups(UpdateContinuousBackupsRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + tableName)
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder()
                        .pointInTimeRecoveryEnabled(true)
                        .build())
                .build());
    }

    /**
     * Updates table to On-Demand billing mode.
     */
    public void updateToOnDemandBilling(String tableName) {
        dynamoDbClient.updateTable(UpdateTableRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
    }

    /**
     * Gets table description for monitoring.
     */
    public TableDescription getTableDescription(String tableName) {
        return dynamoDbClient.describeTable(DescribeTableRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + tableName)
                .build())
                .table();
    }

    /**
     * Tags a table for cost allocation.
     */
    public void tagTable(String tableName, Map<String, String> tags) {
        dynamoDbClient.tagResource(TagResourceRequest.builder()
                .resourceArn("arn:aws:dynamodb:" + awsProperties.getRegion() + ":" + awsProperties.getAccountId() + ":table/" + awsProperties.getDynamodbTablePrefix() + tableName)
                .tags(tags.entrySet().stream()
                        .map(e -> Tag.builder().key(e.getKey()).value(e.getValue()).build())
                        .toList())
                .build());
    }
}