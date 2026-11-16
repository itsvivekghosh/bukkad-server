package com.bhukkad.catalog.aws;

import com.bhukkad.common.aws.AwsProperties;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.core.sync.RequestBody;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Catalog Service AWS integrations.
 * <p>
 * Provides S3 for menu item images, DynamoDB for catalog items,
 * and CloudWatch for catalog metrics.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogAwsService {

    private final S3Client s3Client;
    private final DynamoDbClient dynamoDbClient;
    private final CloudWatchClient cloudWatchClient;
    private final AwsProperties awsProperties;

    public CatalogAwsService(S3Client s3Client,
                             DynamoDbClient dynamoDbClient,
                             CloudWatchClient cloudWatchClient,
                             AwsProperties awsProperties) {
        this.s3Client = s3Client;
        this.dynamoDbClient = dynamoDbClient;
        this.cloudWatchClient = cloudWatchClient;
        this.awsProperties = awsProperties;
    }

    // S3 - Menu Item Images
    public void uploadMenuItemImage(String menuItemId, byte[] imageData, String contentType) {
        String key = "menu-items/" + menuItemId + ".jpg";
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .contentType(contentType)
                .build(),
                RequestBody.fromBytes(imageData));
    }

    public byte[] getMenuItemImage(String menuItemId) throws IOException {
        String key = "menu-items/" + menuItemId + ".jpg";
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .build())
                .readAllBytes();
    }

    public void deleteMenuItemImage(String menuItemId) {
        String key = "menu-items/" + menuItemId + ".jpg";
        s3Client.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .build());
    }

    // DynamoDB - Menu Items
    public void saveMenuItem(String menuItemId, String restaurantId, String name,
                              String description, Double price, String category) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("menuItemId", AttributeValue.fromS(menuItemId));
        item.put("restaurantId", AttributeValue.fromS(restaurantId));
        item.put("name", AttributeValue.fromS(name));
        item.put("description", AttributeValue.fromS(description));
        item.put("price", AttributeValue.fromN(price.toString()));
        item.put("category", AttributeValue.fromS(category));
        item.put("createdAt", AttributeValue.fromS(Instant.now().toString()));
        item.put("isActive", AttributeValue.fromS("true"));

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + "catalog-items")
                .item(item)
                .build());
    }

    public Map<String, AttributeValue> getMenuItem(String menuItemId) {
        return dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + "catalog-items")
                .key(Map.of("menuItemId", AttributeValue.fromS(menuItemId)))
                .build())
                .item();
    }

    // CloudWatch - Catalog Metrics
    public void recordMenuItemView(String menuItemId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                .namespace("Bhukkad/Catalog")
                .metricData(MetricDatum.builder()
                        .metricName("MenuItemViewed")
                        .value(1.0)
                        .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                        .dimensions(Dimension.builder()
                                .name("MenuItemId")
                                .value(menuItemId)
                                .build())
                        .timestamp(Instant.now())
                        .build())
                .build());
    }

    public void recordSearchPerformed(String keyword) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                .namespace("Bhukkad/Catalog")
                .metricData(MetricDatum.builder()
                        .metricName("SearchPerformed")
                        .value(1.0)
                        .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                        .dimensions(Dimension.builder()
                                .name("Keyword")
                                .value(keyword)
                                .build())
                        .timestamp(Instant.now())
                        .build())
                .build());
    }

    public void recordRestaurantListed(String restaurantId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                .namespace("Bhukkad/Catalog")
                .metricData(MetricDatum.builder()
                        .metricName("RestaurantListed")
                        .value(1.0)
                        .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                        .dimensions(Dimension.builder()
                                .name("RestaurantId")
                                .value(restaurantId)
                                .build())
                        .timestamp(Instant.now())
                        .build())
                .build());
    }
}