package com.bhukkad.commerce.aws;

import com.bhukkad.common.aws.AwsProperties;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;


import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Service implementations for Commerce Service.
 * Provides SQS for order processing queue, SNS for order notifications,
 * KMS for payment encryption, DynamoDB for orders.
 */
@Configuration
public class CommerceAwsService {

    private final SqsClient sqsClient;
    private final SnsClient snsClient;
    private final KmsClient kmsClient;
    private final DynamoDbClient dynamoDbClient;
    private final AwsProperties awsProperties;

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

    // ========== SQS Operations - Order Processing Queue ==========

    public void sendOrderToQueue(String orderJson) {
        sqsClient.sendMessage(software.amazon.awssdk.services.sqs.model.SendMessageRequest.builder()
            .queueUrl("http://floci:4566/queue/bhukkad-order-events")
            .messageBody(orderJson)
            .messageAttributes(Map.of(
                "orderType", software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("STANDARD")
                    .build()))
            .build());
    }

    public void sendOrderBatch(java.util.List<String> orderJsons) {
        for (int i = 0; i < orderJsons.size(); i += 10) {
            sqsClient.sendMessageBatch(software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest.builder()
                .queueUrl("http://floci:4566/queue/bhukkad-order-events")
                .entries(orderJsons.subList(i, Math.min(i + 10, orderJsons.size())).stream()
                    .map(orderJson -> software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry.builder()
                        .id(java.util.UUID.randomUUID().toString())
                        .messageBody(orderJson)
                        .build())
                    .collect(java.util.stream.Collectors.toList()))
                .build());
        }
    }

    // ========== SNS Operations - Order Notifications ==========

    public void publishOrderEvent(String orderId, String status, String customerEmail) {
        snsClient.publish(software.amazon.awssdk.services.sns.model.PublishRequest.builder()
            .topicArn("arn:aws:sns:us-east-1:123456789012:bhukkad-order-events")
            .message(new com.bhukkad.commerce.aws.OrderEventJson(
                orderId, status, customerEmail, java.time.Instant.now().toString()
            ).toJson())
            .messageAttributes(Map.of(
                "eventType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("ORDER_STATUS_CHANGED")
                    .build()))
            .build());
    }

    public void publishDeliveryNotification(String orderId, String status, String agentId) {
        snsClient.publish(software.amazon.awssdk.services.sns.model.PublishRequest.builder()
            .topicArn("arn:aws:sns:us-east-1:123456789012:bhukkad-delivery-events")
            .message(new com.bhukkad.commerce.aws.DeliveryEventJson(
                orderId, status, agentId, java.time.Instant.now().toString()
            ).toJson())
            .build());
    }

    // ========== KMS Operations - Payment Encryption ==========

    public byte[] encryptPaymentData(String plaintextPaymentData) {
        return kmsClient.encrypt(software.amazon.awssdk.services.kms.model.EncryptRequest.builder()
            .keyId(awsProperties.getKmsKeyAlias())
            .plaintext(ByteBuffer.wrap(plaintextPaymentData.getBytes(StandardCharsets.UTF_8)))
            .build())
            .ciphertextBlob()
            .asBytes();
    }

    public byte[] decryptPaymentData(byte[] ciphertext) {
        return kmsClient.decrypt(software.amazon.awssdk.services.kms.model.DecryptRequest.builder()
            .ciphertextBlob(ByteBuffer.wrap(ciphertext))
            .build())
            .plaintext()
            .asBytes();
    }

    // ========== DynamoDB Operations - Orders ==========

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

        dynamoDbClient.putItem(software.amazon.awssdk.services.dynamodb.model.PutItemRequest.builder()
            .tableName("bhukkad-orders")
            .item(item)
            .build());
    }

    public Map<String, AttributeValue> getOrder(String orderId) {
        return dynamoDbClient.getItem(software.amazon.awssdk.services.dynamodb.model.GetItemRequest.builder()
            .tableName("bhukkad-orders")
            .key(Map.of("orderId", AttributeValue.fromS(orderId)))
            .build())
            .item();
    }

    public void updateOrderStatus(String orderId, String newStatus) {
        dynamoDbClient.updateItem(software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.builder()
            .tableName("bhukkad-orders")
            .key(Map.of("orderId", AttributeValue.fromS(orderId)))
            .updateExpression("SET #status = :status, updatedAt = :updatedAt")
            .expressionAttributeNames(Map.of("#status", "status"))
            .expressionAttributeValues(Map.of(
                ":status", AttributeValue.fromS(newStatus),
                ":updatedAt", AttributeValue.fromS(Instant.now().toString())
            ))
            .build());
    }

    // ========== Single-Table Design for Order History ==========
    // Uses composite key pattern: PK=ORDER#{orderId}, SK=ORDER#DETAIL#{orderId}
    // Enables efficient querying of all order details by orderId

    public void saveOrderWithDetails(String orderId, String customerId, String restaurantId,
                                    String status, Double totalAmount, String itemsJson,
                                    String deliveryAddress, String paymentMethod) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS("ORDER#" + orderId));
        item.put("SK", AttributeValue.fromS("ORDER#DETAIL#" + orderId));
        item.put("orderId", AttributeValue.fromS(orderId));
        item.put("customerId", AttributeValue.fromS(customerId));
        item.put("restaurantId", AttributeValue.fromS(restaurantId));
        item.put("status", AttributeValue.fromS(status));
        item.put("totalAmount", AttributeValue.fromN(totalAmount.toString()));
        item.put("itemsJson", AttributeValue.fromS(itemsJson));
        item.put("deliveryAddress", AttributeValue.fromS(deliveryAddress));
        item.put("paymentMethod", AttributeValue.fromS(paymentMethod));
        item.put("createdAt", AttributeValue.fromS(Instant.now().toString()));
        item.put("entityType", AttributeValue.fromS("ORDER"));

        dynamoDbClient.putItem(software.amazon.awssdk.services.dynamodb.model.PutItemRequest.builder()
            .tableName("bhukkad-orders")
            .item(item)
            .build());
    }

    public void updateOrderStatusByOrderId(String orderId, String newStatus) {
        dynamoDbClient.updateItem(software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.builder()
            .tableName("bhukkad-orders")
            .key(Map.of("PK", AttributeValue.fromS("ORDER#" + orderId),
                        "SK", AttributeValue.fromS("ORDER#DETAIL#" + orderId)))
            .updateExpression("SET #status = :status, updatedAt = :updatedAt")
            .expressionAttributeNames(Map.of("#status", "status"))
            .expressionAttributeValues(Map.of(
                ":status", AttributeValue.fromS(newStatus),
                ":updatedAt", AttributeValue.fromS(Instant.now().toString())
            ))
            .build());
    }

    public Map<String, AttributeValue> getOrderById(String orderId) {
        return dynamoDbClient.getItem(software.amazon.awssdk.services.dynamodb.model.GetItemRequest.builder()
            .tableName("bhukkad-orders")
            .key(Map.of("PK", AttributeValue.fromS("ORDER#" + orderId),
                        "SK", AttributeValue.fromS("ORDER#DETAIL#" + orderId)))
            .build())
            .item();
    }

    public void addOrderItem(String orderId, String itemId, String itemName, Double price, Integer quantity) {
        dynamoDbClient.putItem(software.amazon.awssdk.services.dynamodb.model.PutItemRequest.builder()
            .tableName("bhukkad-orders")
            .item(Map.of(
                "PK", AttributeValue.fromS("ORDER#" + orderId),
                "SK", AttributeValue.fromS("ITEM#" + itemId),
                "orderId", AttributeValue.fromS(orderId),
                "itemId", AttributeValue.fromS(itemId),
                "itemName", AttributeValue.fromS(itemName),
                "price", AttributeValue.fromN(price.toString()),
                "quantity", AttributeValue.fromN(quantity.doubleValue()),
                "entityType", AttributeValue.fromS("ORDER_ITEM")
            ))
            .build());
    }

    public Map<String, AttributeValue> getOrderItems(String orderId) {
        return dynamoDbClient.getItem(software.amazon.awssdk.services.dynamodb.model.GetItemRequest.builder()
            .tableName("bhukkad-orders")
            .key(Map.of("PK", AttributeValue.fromS("ORDER#" + orderId),
                        "SK", AttributeValue.fromS("ORDER#DETAIL#" + orderId)))
            .build())
            .item();
    }

    public void addOrderTimelineEvent(String orderId, String status, String description) {
        dynamoDbClient.putItem(software.amazon.awssdk.services.dynamodb.model.PutItemRequest.builder()
            .tableName("bhukkad-orders")
            .item(Map.of(
                "PK", AttributeValue.fromS("ORDER#" + orderId),
                "SK", AttributeValue.fromS("TIMELINE#" + Instant.now().toString()),
                "orderId", AttributeValue.fromS(orderId),
                "status", AttributeValue.fromS(status),
                "description", AttributeValue.fromS(description),
                "timestamp", AttributeValue.fromS(Instant.now().toString()),
                "entityType", AttributeValue.fromS("TIMELINE_EVENT")
            ))
            .build());
    }

    public Map<String, AttributeValue> getOrderTimeline(String orderId) {
        return dynamoDbClient.getItem(software.amazon.awssdk.services.dynamodb.model.GetItemRequest.builder()
            .tableName("bhukkad-orders")
            .key(Map.of("PK", AttributeValue.fromS("ORDER#" + orderId),
                        "SK", AttributeValue.fromS("ORDER#DETAIL#" + orderId)))
            .build())
            .item();
    }

    // Inner classes for JSON serialization
    public static class OrderEventJson {
        private String orderId;
        private String status;
        private String customerEmail;
        private String timestamp;

        public OrderEventJson() {}

        public OrderEventJson(String orderId, String status, String customerEmail, String timestamp) {
            this.orderId = orderId;
            this.status = status;
            this.customerEmail = customerEmail;
            this.timestamp = timestamp;
        }

        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getCustomerEmail() { return customerEmail; }
        public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        public String toJson() {
            return "{\"orderId\":\"" + orderId + "\",\"status\":\"" + status +
                "\",\"customerEmail\":\"" + customerEmail + "\",\"timestamp\":\"" + timestamp + "\"}";
        }
    }

    public static class DeliveryEventJson {
        private String orderId;
        private String status;
        private String agentId;
        private String timestamp;

        public DeliveryEventJson() {}

        public DeliveryEventJson(String orderId, String status, String agentId, String timestamp) {
            this.orderId = orderId;
            this.status = status;
            this.agentId = agentId;
            this.timestamp = timestamp;
        }

        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        public String toJson() {
            return "{\"orderId\":\"" + orderId + "\",\"status\":\"" + status +
                "\",\"agentId\":\"" + agentId + "\",\"timestamp\":\"" + timestamp + "\"}";
        }
    }
}
