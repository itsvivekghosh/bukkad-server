package com.bhukkad.engagement.aws;

import com.bhukkad.common.aws.AwsProperties;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Service implementations for Engagement Service.
 * Provides SNS for social notifications, DynamoDB for social posts,
 * CloudWatch for engagement metrics.
 */
@Configuration
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

    // ========== SNS Operations - Social Notifications ==========

    public void publishSocialPostNotification(String postId, String authorId, String content) {
        snsClient.publish(software.amazon.awssdk.services.sns.model.PublishRequest.builder()
            .topicArn("arn:aws:sns:us-east-1:123456789012:bhukkad-social-events")
            .message(com.bhukkad.engagement.aws.SocialEventJson.builder()
                .postId(postId)
                .authorId(authorId)
                .content(content)
                .timestamp(java.time.Instant.now().toString())
                .build()
                .toJson())
            .messageAttributes(Map.of(
                "eventType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("SOCIAL_POST_CREATED")
                    .build()))
            .build());
    }

    public void publishLikeNotification(String postId, String likerId, String authorId) {
        snsClient.publish(software.amazon.awssdk.services.sns.model.PublishRequest.builder()
            .topicArn("arn:aws:sns:us-east-1:123456789012:bhukkad-social-events")
            .message(com.bhukkad.engagement.aws.SocialEventJson.builder()
                .postId(postId)
                .authorId(authorId)
                .content("User " + likerId + " liked your post")
                .timestamp(java.time.Instant.now().toString())
                .build()
                .toJson())
            .messageAttributes(Map.of(
                "eventType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("SOCIAL_POST_LIKED")
                    .build()))
            .build());
    }

    public void publishCommentNotification(String postId, String commenterId, String authorId, String commentText) {
        snsClient.publish(software.amazon.awssdk.services.sns.model.PublishRequest.builder()
            .topicArn("arn:aws:sns:us-east-1:123456789012:bhukkad-social-events")
            .message(com.bhukkad.engagement.aws.SocialEventJson.builder()
                .postId(postId)
                .authorId(authorId)
                .content(commenterId + " commented: " + commentText)
                .timestamp(java.time.Instant.now().toString())
                .build()
                .toJson())
            .messageAttributes(Map.of(
                "eventType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("SOCIAL_COMMENT_ADDED")
                    .build()))
            .build());
    }

    // ========== DynamoDB Operations - Social Posts ==========

    public void saveSocialPost(String postId, String authorId, String content,
                                String latitude, String longitude) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("postId", AttributeValue.fromS(postId));
        item.put("authorId", AttributeValue.fromS(authorId));
        item.put("content", AttributeValue.fromS(content));
        item.put("latitude", AttributeValue.fromS(latitude));
        item.put("longitude", AttributeValue.fromS(longitude));
        item.put("createdAt", AttributeValue.fromS(Instant.now().toString()));
        item.put("likes", AttributeValue.fromN("0"));
        item.put("comments", AttributeValue.fromN("0"));
        item.put("isActive", AttributeValue.fromS("true"));

        dynamoDbClient.putItem(software.amazon.awssdk.services.dynamodb.model.PutItemRequest.builder()
            .tableName("bhukkad-social-posts")
            .item(item)
            .build());
    }

    public Map<String, AttributeValue> getSocialPost(String postId) {
        return dynamoDbClient.getItem(software.amazon.awssdk.services.dynamodb.model.GetItemRequest.builder()
            .tableName("bhukkad-social-posts")
            .key(Map.of("postId", AttributeValue.fromS(postId)))
            .build())
            .item();
    }

    public void incrementLikeCount(String postId) {
        dynamoDbClient.updateItem(software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.builder()
            .tableName("bhukkad-social-posts")
            .key(Map.of("postId", AttributeValue.fromS(postId)))
            .updateExpression("SET likes = likes + :inc")
            .expressionAttributeValues(Map.of(":inc", AttributeValue.fromN("1")))
            .build());
    }

    public void incrementCommentCount(String postId) {
        dynamoDbClient.updateItem(software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.builder()
            .tableName("bhukkad-social-posts")
            .key(Map.of("postId", AttributeValue.fromS(postId)))
            .updateExpression("SET comments = comments + :inc")
            .expressionAttributeValues(Map.of(":inc", AttributeValue.fromN("1")))
            .build());
    }

    // ========== CloudWatch Operations - Engagement Metrics ==========

    public void recordSocialPostCreated(String postId, String authorId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Engagement")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("SocialPostCreated")
                .value(1.0)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
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
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("SocialPostLiked")
                .value(1.0)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("PostId")
                    .value(postId)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }

    public void recordCommentAdded(String postId, String commenterId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Engagement")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("SocialCommentAdded")
                .value(1.0)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("PostId")
                    .value(postId)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }
}
