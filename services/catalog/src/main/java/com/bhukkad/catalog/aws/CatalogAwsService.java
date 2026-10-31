package com.bhukkad.catalog.aws;

import com.bhukkad.common.aws.AwsProperties;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.core.sync.RequestBody;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Service implementations for Catalog Service.
 * Provides S3 for menu item images, DynamoDB for catalog items,
 * CloudWatch for catalog metrics.
 */
@Configuration
public class CatalogAwsService {

    private final S3Client s3Client;
    private final DynamoDbClient dynamoDbClient;
    private final CloudWatchClient cloudWatchClient;
    private final LambdaClient lambdaClient;
    private final AthenaClient athenaClient;
    private final AwsProperties awsProperties;

    public CatalogAwsService(S3Client s3Client,
                             DynamoDbClient dynamoDbClient,
                             CloudWatchClient cloudWatchClient,
                             LambdaClient lambdaClient,
                             AthenaClient athenaClient,
                             AwsProperties awsProperties) {
        this.s3Client = s3Client;
        this.dynamoDbClient = dynamoDbClient;
        this.cloudWatchClient = cloudWatchClient;
        this.lambdaClient = lambdaClient;
        this.athenaClient = athenaClient;
        this.awsProperties = awsProperties;
    }

    // ========== S3 Operations - Menu Item Images ==========

    public void uploadMenuItemImage(String menuItemId, byte[] imageData, String contentType) {
        String key = "menu-items/" + menuItemId + ".jpg";
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .contentType(contentType)
                .build(),
            RequestBody.fromBytes(imageData));
    }

    public byte[] getMenuItemImage(String menuItemId) {
        String key = "menu-items/" + menuItemId + ".jpg";
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .build())
            .readAllBytes();
    }

    public void deleteMenuItemImage(String menuItemId) {
        s3Client.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key("menu-items/" + menuItemId + ".jpg")
                .build());
    }

    // ========== DynamoDB Operations - Menu Items ==========

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
                .tableName("bhukkad-catalog-items")
                .item(item)
                .build());
    }

    public Map<String, AttributeValue> getMenuItem(String menuItemId) {
        return dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName("bhukkad-catalog-items")
                .key(Map.of("menuItemId", AttributeValue.fromS(menuItemId)))
                .build())
            .item();
    }

    public void updateMenuItemRating(String menuItemId, Double newRating) {
        dynamoDbClient.updateItem(software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.builder()
            .tableName("bhukkad-catalog-items")
            .key(Map.of("menuItemId", AttributeValue.fromS(menuItemId)))
            .updateExpression("SET avgRating = :rating")
            .expressionAttributeValues(Map.of(":rating", AttributeValue.fromN(newRating.toString())))
            .build());
    }

    // ========== CloudWatch Operations - Catalog Metrics ==========

    public void recordMenuItemView(String menuItemId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Catalog")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("MenuItemViewed")
                .value(1.0)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("MenuItemId")
                    .value(menuItemId)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }

    public void recordRestaurantView(String restaurantId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Catalog")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("RestaurantViewed")
                .value(1.0)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("RestaurantId")
                    .value(restaurantId)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }

    public void recordSearchQuery(String keyword, int resultCount) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Catalog")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("SearchQuery")
                .value(1.0)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("Keyword")
                    .value(keyword)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }

    // ========== AWS Lambda - Menu Item Image Processing ==========

    public String invokeImageResize(String imageName, byte[] imageData, String targetSize) {
        return lambdaClient.invoke(software.amazon.awssdk.services.lambda.model.InvokeRequest.builder()
            .functionName("image-resizer")
            .payload(software.amazon.awssdk.core.SdkBytes.fromUtf8String(
                String.format("{\"imageName\": \"%s\", \"imageData\": \"%s\", \"targetSize\": \"%s\"}",
                    imageName, new String(imageData, StandardCharsets.UTF_8), targetSize)))
            .build())
            .payload()
            .asUtf8String();
    }

    public void uploadAndProcessMenuItemImage(String menuItemId, byte[] imageData, String contentType) {
        // Upload original image to S3
        String imageKey = "menu-items/" + menuItemId + ".jpg";
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(imageKey)
                .contentType(contentType)
                .build(),
            RequestBody.fromBytes(imageData));

        // Trigger Lambda to process image (resize, thumbnail, etc.)
        String lambdaResult = invokeImageResize(menuItemId, imageData, "thumbnail");

        // Record image upload metric
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Catalog")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("MenuItemImageUploaded")
                .value(1.0)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("MenuItemId")
                    .value(menuItemId)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }

    // ========== Athena Analytics - Ad-hoc Analytics on S3 Data ==========

    public void executeAthenaQuery(String queryString, String outputLocation) {
        athenaClient.startQueryExecution(software.amazon.awssdk.services.athena.model.StartQueryExecutionRequest.builder()
            .queryString(queryString)
            .queryExecutionContext(software.amazon.awssdk.services.athena.model.QueryExecutionContext.builder()
                .database("bhukkad_analytics")
                .build())
            .resultConfiguration(software.amazon.awssdk.services.athena.model.ResultConfiguration.builder()
                .outputLocation("s3://" + awsProperties.getS3Bucket() + "/" + outputLocation)
                .build())
            .build());
    }

    public void getQueryExecution(String queryExecutionId) {
        athenaClient.getQueryExecution(software.amazon.awssdk.services.athena.model.GetQueryExecutionRequest.builder()
            .queryExecutionId(queryExecutionId)
            .build());
    }

    public void getQueryResults(String queryExecutionId) {
        athenaClient.getQueryResults(software.amazon.awssdk.services.athena.model.GetQueryResultsRequest.builder()
            .queryExecutionId(queryExecutionId)
            .build());
    }

    public void runPopularMenuItemsQuery() {
        String query = "SELECT menuItemId, COUNT(*) as viewCount FROM bhukkad_menu_views " +
                "GROUP BY menuItemId ORDER BY viewCount DESC LIMIT 10";
        executeAthenaQuery(query, "athena-results/");
    }

    public void runPopularRestaurantsQuery() {
        String query = "SELECT restaurantId, COUNT(*) as orderCount FROM bhukkad-orders " +
                "GROUP BY restaurantId ORDER BY orderCount DESC LIMIT 10";
        executeAthenaQuery(query, "athena-results/");
    }
}
