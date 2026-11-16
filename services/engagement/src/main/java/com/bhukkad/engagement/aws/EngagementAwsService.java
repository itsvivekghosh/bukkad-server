package com.bhukkad.engagement.aws;

import com.bhukkad.common.aws.AwsProperties;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Engagement Service AWS integrations.
 * <p>
 * Provides SNS for social notifications, DynamoDB for social posts,
 * and CloudWatch for engagement metrics.
 */
@Configuration(proxyBeanMethods = false)
public class EngagementAwsService {

    private final SnsClient snsClient;
    private final DynamoDbClient dynamoDbClient;
    private final CloudWatchClient cloudWatchClient;
    private final AwsProperties awsProperties;

    public EngagementAwsService(SnsClient snsClient,
                                DynamoDbClient dynamoDbClient,
                                CloudWatchClient cloudWatchClient,
                                AwsProperties awsProperties) {
        this.snsClient = snsClient;
        this.dynamoDbClient = dynamoDbClient;
        this.cloudWatchClient = cloudWatchClient;
        this.awsProperties = awsProperties;
    }

    // SNS - Social Notifications
    public void publishSocialPostNotification(String postId, String authorId, String content) {
        String message = String.format("{\"postId\":\"%s\",\"authorId\":\"%s\",\"content\":\"%s\",\"timestamp\":\"%s\"}",
                postId, authorId, content, Instant.now().toString());

        snsClient.publish(PublishRequest.builder()
                .topicArn("arn:aws:sns:us-east-1:123456789012:" + awsProperties.getSnsTopicPrefix() + "social-events")
                .message(message)
                .build());
    }

    public void publishNotificationEvent(String userId, String notificationType, String message) {
        String notificationJson = String.format("{\"userId\":\"%s\",\"type\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}",
                userId, notificationType, message, Instant.now().toString());

        snsClient.publish(PublishRequest.builder()
                .topicArn("arn:aws:sns:us-east-1:123456789012:" + awsProperties.getSnsTopicPrefix() + "notification-events")
                .message(notificationJson)
                .messageAttributes(Map.of(
                        "eventType", MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue("NOTIFICATION")
                                .build()))
                .build());
    }

    // DynamoDB - Social Posts
    public void saveSocialPost(String postId, String ownerId, String content,
                                String latitude, String longitude) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("postId", AttributeValue.fromS(postId));
        item.put("authorId", AttributeValue.fromS(ownerId));
        item.put("content", AttributeValue.fromS(content));
        item.put("latitude", AttributeValue.fromS(latitude));
        item.put("longitude", AttributeValue.fromS(longitude));
        item.put("createdAt", AttributeValue.fromS(Instant.now().toString()));
        item.put("likes", AttributeValue.fromN("0"));
        item.put("comments", AttributeValue.fromN("0"));
        item.put("isActive", AttributeValue.fromS("true"));

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + "social-posts")
                .item(item)
                .build());
    }

    public Map<String, AttributeValue> getSocialPost(String postId) {
        return dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + "social-posts")
                .key(Map.of("postId", AttributeValue.fromS(postId)))
                .build())
                .item();
    }

    public void incrementLikeCount(String postId) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + "social-posts")
                .key(Map.of("postId", AttributeValue.fromS(postId)))
                .updateExpression("SET likes = likes + :inc")
                .expressionAttributeValues(Map.of(":inc", AttributeValue.fromN("1")))
                .build());
    }

    public void incrementCommentCount(String postId) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + "social-posts")
                .key(Map.of("postId", AttributeValue.fromS(postId)))
                .updateExpression("SET comments = comments + :inc")
                .expressionAttributeValues(Map.of(":inc", AttributeValue.fromN("1")))
                .build());
    }

    // CloudWatch - Engagement Metrics
    public void recordSocialPostCreated(String postId, String authorId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                .namespace("Bhukkad/Engagement")
                .metricData(MetricDatum.builder()
                        .metricName("SocialPostCreated")
                        .value(1.0)
                        .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                        .dimensions(Dimension.builder()
                                .name("AuthorId")
                                .value(authorId)
                                .build())
                        .timestamp(Instant.now())
                        .build())
                .build());
    }

    public void recordLikeAction(String postId, String likerId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                .namespace("Bhukkad/Engagement")
                .metricData(MetricDatum.builder()
                        .metricName("SocialPostLiked")
                        .value(1.0)
                        .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                        .dimensions(Dimension.builder()
                                .name("PostId")
                                .value(postId)
                                .build())
                        .timestamp(Instant.now())
                        .build())
                .build());
    }

    public void recordCommentAction(String postId, String commenterId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                .namespace("Bhukkad/Engagement")
                .metricData(MetricDatum.builder()
                        .metricName("SocialPostCommented")
                        .value(1.0)
                        .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                        .dimensions(Dimension.builder()
                                .name("PostId")
                                .value(postId)
                                .build())
                        .timestamp(Instant.now())
                        .build())
                .build());
    }
}