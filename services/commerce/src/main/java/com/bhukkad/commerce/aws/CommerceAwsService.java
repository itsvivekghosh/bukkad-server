package com.bhukkad.commerce.aws;

import com.bhukkad.common.aws.AwsProperties;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.CreatePlatformEndpointRequest;
import software.amazon.awssdk.services.sns.model.SetSubscriptionAttributesRequest;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.kms.KmsClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.model.*;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.core.sync.RequestBody;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Commerce Service AWS integrations.
 * <p>
 * Provides SQS for order processing, SNS for order notifications,
 * KMS for payment encryption, and DynamoDB for orders.
 */
@Configuration(proxyBeanMethods = false)
public class CommerceAwsService {

    private final SqsClient sqsClient;
    private final SnsClient snsClient;
    private final KmsClient kmsClient;
    private final DynamoDbClient dynamoDbClient;
    private final AwsProperties awsProperties;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public CommerceAwsService(SqsClient sqsClient,
                              SnsClient snsClient,
                              KmsClient kmsClient,
                              DynamoDbClient dynamoDbClient,
                              AwsProperties awsProperties) {
        this.sqsClient = sqsClient;
        this.snsClient = snsClient;
        this.kmsClient = kmsClient;
        this.dynamoDbClient = dynamoDbClient;
        this.awsProperties = awsProperties;
    }

    // SQS - Order Processing
    public void sendOrderToQueue(String orderJson) {
        sendOrderToQueueInternal(orderJson, null);
    }

    public void sendOrderToQueueWithDeduplication(String orderJson, String deduplicationId) {
        sendOrderToQueueInternal(orderJson, deduplicationId);
    }

    private void sendOrderToQueueInternal(String orderJson, String deduplicationId) {
        String queueUrl = awsProperties.getEndpoint() + "/queue/" + awsProperties.getSqsQueuePrefix() + "order-events.fifo";
        
        SendMessageRequest.Builder builder = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(orderJson)
                .messageGroupId("order-processing")  // Required for FIFO
                .messageAttributes(Map.<String, software.amazon.awssdk.services.sqs.model.MessageAttributeValue>of(
                        "orderType", software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue("STANDARD")
                                .build()));

        if (deduplicationId != null) {
            builder.messageDeduplicationId(deduplicationId);
        }

        sqsClient.sendMessage(builder.build());
    }

    public void sendOrderBatch(List<String> orderJsons) {
        sendOrderBatchInternal(orderJsons, null);
    }

    public void sendOrderBatchWithDeduplication(List<String> orderJsons, List<String> deduplicationIds) {
        sendOrderBatchInternal(orderJsons, deduplicationIds);
    }

    private void sendOrderBatchInternal(List<String> orderJsons, List<String> deduplicationIds) {
        String queueUrl = awsProperties.getEndpoint() + "/queue/" + awsProperties.getSqsQueuePrefix() + "order-events.fifo";
        
        for (int i = 0; i < orderJsons.size(); i += 10) {
            List<SendMessageBatchRequestEntry> entries = orderJsons.subList(i, Math.min(i + 10, orderJsons.size())).stream()
                    .map(orderJson -> {
                        SendMessageBatchRequestEntry.Builder entryBuilder = SendMessageBatchRequestEntry.builder()
                                .id(UUID.randomUUID().toString())
                                .messageBody(orderJson)
                                .messageGroupId("order-processing");
                        
                        int index = orderJsons.indexOf(orderJson);
                        if (deduplicationIds != null && index < deduplicationIds.size()) {
                            entryBuilder.messageDeduplicationId(deduplicationIds.get(index));
                        }
                        
                        return entryBuilder.build();
                    })
                    .collect(Collectors.toList());

            sqsClient.sendMessageBatch(SendMessageBatchRequest.builder()
                    .queueUrl(queueUrl)
                    .entries(entries)
                    .build());
        }
    }

    /**
     * Creates a FIFO queue with Dead Letter Queue configuration.
     * Should be called during service initialization.
     */
    public void createOrderFifoQueueWithDlq() {
        String queueName = awsProperties.getSqsQueuePrefix() + "order-events.fifo";
        String dlqName = awsProperties.getSqsQueuePrefix() + "order-events-dlq.fifo";
        String endpoint = awsProperties.getEndpoint();

        // Create DLQ first
        CreateQueueRequest dlqRequest = CreateQueueRequest.builder()
                .queueName(dlqName)
                .attributes(Map.<QueueAttributeName, String>of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false",
                        QueueAttributeName.MESSAGE_RETENTION_PERIOD, "1209600"))  // 14 days
                .build();
        String dlqUrl = sqsClient.createQueue(dlqRequest).queueUrl();

        // Get DLQ ARN
        GetQueueAttributesRequest dlqAttrRequest = GetQueueAttributesRequest.builder()
                .queueUrl(dlqUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build();
        String dlqArn = sqsClient.getQueueAttributes(dlqAttrRequest).attributes().get(QueueAttributeName.QUEUE_ARN);

        // Create main FIFO queue with DLQ
        CreateQueueRequest queueRequest = CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(Map.<QueueAttributeName, String>of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false",
                        QueueAttributeName.MESSAGE_RETENTION_PERIOD, "345600",  // 4 days
                        QueueAttributeName.REDRIVE_POLICY, String.format("{\"deadLetterTargetArn\":\"%s\",\"maxReceiveCount\":3}", dlqArn)))
                .build();

        sqsClient.createQueue(queueRequest);
    }

    /**
     * Receives messages from the order queue with long polling.
     */
    public List<Message> receiveOrderMessages(int maxMessages, int waitTimeSeconds) {
        String queueUrl = awsProperties.getEndpoint() + "/queue/" + awsProperties.getSqsQueuePrefix() + "order-events.fifo";
        
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(Math.min(maxMessages, 10))
                .waitTimeSeconds(waitTimeSeconds)
                .messageAttributeNames("All")
                .build();

        return sqsClient.receiveMessage(request).messages();
    }

    /**
     * Deletes a processed message from the queue.
     */
    public void deleteMessage(String receiptHandle) {
        String queueUrl = awsProperties.getEndpoint() + "/queue/" + awsProperties.getSqsQueuePrefix() + "order-events.fifo";
        
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build());
    }

/**
     * Creates an SNS FIFO topic for ordered notifications.
     */
    public void createOrderNotificationsFifoTopic() {
        String topicName = awsProperties.getSnsTopicPrefix() + "order-events.fifo";

        CreateTopicRequest request = CreateTopicRequest.builder()
                .name(topicName)
                .attributes(Map.of(
                        "FifoTopic", "true",
                        "ContentBasedDeduplication", "false"))
                .build();

        snsClient.createTopic(request);
    }

    /**
     * Publishes an order event to a FIFO topic with message deduplication.
     */
    public void publishOrderEventFifo(String orderId, String status, String customerEmail, String deduplicationId) {
        String message = String.format("{\"orderId\":\"%s\",\"status\":\"%s\",\"customerEmail\":\"%s\",\"timestamp\":\"%s\"}",
                orderId, status, customerEmail, Instant.now().toString());

        String topicArn = "arn:aws:sns:" + awsProperties.getRegion() + ":" + awsProperties.getAccountId() + ":" + awsProperties.getSnsTopicPrefix() + "order-events.fifo";

        PublishRequest.Builder builder = PublishRequest.builder()
                .topicArn(topicArn)
                .message(message)
                .messageGroupId("order-notifications")  // Required for FIFO
                .messageAttributes(Map.of(
                        "eventType", MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue("ORDER_STATUS_CHANGED")
                                .build()));

        if (deduplicationId != null) {
            builder.messageDeduplicationId(deduplicationId);
        }

        snsClient.publish(builder.build());
    }

    /**
     * Publishes a mobile push notification with raw message delivery.
     * <p>
     * Raw message delivery sends the message directly to the push service (FCM/APNs)
     * without SNS JSON wrapping, reducing payload size and latency.
     */
    public void publishMobilePush(String targetArn, String title, String body, Map<String, String> data) {
        String message = String.format("{\"notification\":{\"title\":\"%s\",\"body\":\"%s\"},\"data\":%s}",
                title, body, toJson(data));

        snsClient.publish(PublishRequest.builder()
                .targetArn(targetArn)
                .message(message)
                .messageAttributes(Map.of(
                        "raw", MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue("true")
                                .build()))
                .build());
    }

    /**
     * Creates a platform endpoint for mobile push (FCM/APNs).
     */
    public String createPlatformEndpoint(String platformApplicationArn, String token, Map<String, String> attributes) {
        return snsClient.createPlatformEndpoint(CreatePlatformEndpointRequest.builder()
                .platformApplicationArn(platformApplicationArn)
                .token(token)
                .customUserData("Bhukkad Mobile User")
                .attributes(attributes)
                .build())
                .endpointArn();
    }

    /**
     * Sets subscription attributes for message filtering.
     * <p>
     * Allows subscribers to receive only messages matching their filter policy.
     */
    public void setSubscriptionFilterPolicy(String subscriptionArn, Map<String, List<String>> filterPolicy) {
        snsClient.setSubscriptionAttributes(SetSubscriptionAttributesRequest.builder()
                .subscriptionArn(subscriptionArn)
                .attributeName("FilterPolicy")
                .attributeValue(toJson(filterPolicy))
                .build());
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize to JSON", e);
        }
    }

    /**
     * Creates an SQS FIFO queue with message filtering (using message attributes).
     */
    public void createFilteredOrderQueue(String queueName, Map<String, List<String>> filterPolicy) {
        String fullQueueName = awsProperties.getSqsQueuePrefix() + queueName + ".fifo";

        CreateQueueRequest request = CreateQueueRequest.builder()
                .queueName(fullQueueName)
                .attributes(Map.<QueueAttributeName, String>of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false",
                        QueueAttributeName.MESSAGE_RETENTION_PERIOD, "345600",
                        QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, "20"))  // Long polling default
                .build();

        sqsClient.createQueue(request);
    }

    /**
     * Receives messages with long polling and visibility timeout.
     */
    public List<Message> receiveOrderMessagesLongPoll(int maxMessages, int waitTimeSeconds, int visibilityTimeout) {
        String queueUrl = awsProperties.getEndpoint() + "/queue/" + awsProperties.getSqsQueuePrefix() + "order-events.fifo";

        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(Math.min(maxMessages, 10))
                .waitTimeSeconds(waitTimeSeconds)
                .visibilityTimeout(visibilityTimeout)
                .messageAttributeNames("All")
                .messageSystemAttributeNamesWithStrings("All")
                .build();

        return sqsClient.receiveMessage(request).messages();
    }

    /**
     * Changes message visibility timeout (extends processing time).
     */
    public void changeMessageVisibility(String receiptHandle, int visibilityTimeout) {
        String queueUrl = awsProperties.getEndpoint() + "/queue/" + awsProperties.getSqsQueuePrefix() + "order-events.fifo";

        sqsClient.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .visibilityTimeout(visibilityTimeout)
                .build());
    }

    /**
     * Purges a queue (removes all messages).
     */
    public void purgeQueue(String queueName) {
        String queueUrl = awsProperties.getEndpoint() + "/queue/" + awsProperties.getSqsQueuePrefix() + queueName;
        sqsClient.purgeQueue(PurgeQueueRequest.builder()
                .queueUrl(queueUrl)
                .build());
    }

    /**
     * Gets queue attributes for monitoring.
     */
    public Map<QueueAttributeName, String> getQueueAttributes(String queueName) {
        String queueUrl = awsProperties.getEndpoint() + "/queue/" + awsProperties.getSqsQueuePrefix() + queueName;

        return sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.ALL)
                .build())
                .attributes();
    }

    // SNS - Order Notifications
    public void publishOrderEvent(String orderId, String status, String customerEmail) {
        String message = String.format("{\"orderId\":\"%s\",\"status\":\"%s\",\"customerEmail\":\"%s\",\"timestamp\":\"%s\"}",
                orderId, status, customerEmail, Instant.now().toString());

        snsClient.publish(PublishRequest.builder()
                .topicArn("arn:aws:sns:us-east-1:123456789012:" + awsProperties.getSnsTopicPrefix() + "order-events")
                .message(message)
                .messageAttributes(Map.of(
                        "eventType", MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue("ORDER_STATUS_CHANGED")
                                .build()))
                .build());
    }

    // KMS - Payment Encryption
    public byte[] encryptPaymentData(String plaintextPaymentData) {
        return kmsClient.encrypt(EncryptRequest.builder()
                .keyId(awsProperties.getKmsKeyAlias())
                .plaintext(SdkBytes.fromByteBuffer(ByteBuffer.wrap(plaintextPaymentData.getBytes(StandardCharsets.UTF_8))))
                .build())
                .ciphertextBlob()
                .asByteArray();
    }

    public byte[] decryptPaymentData(byte[] ciphertext) {
        return kmsClient.decrypt(DecryptRequest.builder()
                .ciphertextBlob(SdkBytes.fromByteBuffer(ByteBuffer.wrap(ciphertext)))
                .build())
                .plaintext()
                .asByteArray();
    }

    // DynamoDB - Orders
    public void saveOrder(String orderId, String customerId, String restaurantId,
                          String status, Double totalAmount, String itemsJson) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("orderId", AttributeValue.fromS(orderId));
        item.put("customerId", AttributeValue.fromS(customerId));
        item.put("restaurantId", AttributeValue.fromS(restaurantId));
        item.put("status", AttributeValue.fromS(status));
        item.put("totalAmount", AttributeValue.fromN(totalAmount.toString()));
        item.put("itemsJson", AttributeValue.fromS(itemsJson));
        item.put("createdAt", AttributeValue.fromS(Instant.now().toString()));
        item.put("isActive", AttributeValue.fromS("true"));

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + "orders")
                .item(item)
                .build());
    }

    public Map<String, AttributeValue> getOrder(String orderId) {
        return dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + "orders")
                .key(Map.of("orderId", AttributeValue.fromS(orderId)))
                .build())
                .item();
    }

    public void updateOrderStatus(String orderId, String newStatus) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(awsProperties.getDynamodbTablePrefix() + "orders")
                .key(Map.of("orderId", AttributeValue.fromS(orderId)))
                .updateExpression("SET #status = :status, updatedAt = :updatedAt")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":status", AttributeValue.fromS(newStatus),
                        ":updatedAt", AttributeValue.fromS(Instant.now().toString())
                ))
                .build());
    }
}