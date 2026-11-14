package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.model.*;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SQS Extended Client for handling messages larger than 256KB.
 * <p>
 * SQS has a 256KB message size limit. For larger messages, the SQS Extended
 * Client stores the payload in S3 and sends a reference (pointer) in the SQS
 * message. Consumers retrieve the full payload from S3 using the reference.
 * <p>
 * This supports payloads up to 2GB, enabling large order payloads, order images,
 * and document attachments to be processed through SQS queues.
 */
@Service
public class SqsExtendedClientService {

    private final SqsClient sqsClient;
    private final S3Client s3Client;
    private final AwsProperties awsProperties;

    private static final int SQS_MESSAGE_SIZE_LIMIT = 256 * 1024; // 256 KB
    private static final String S3_POINTER_PREFIX = "sqs-extended/";

    public SqsExtendedClientService(SqsClient sqsClient, S3Client s3Client, AwsProperties awsProperties) {
        this.sqsClient = sqsClient;
        this.s3Client = s3Client;
        this.awsProperties = awsProperties;
    }

    /**
     * Sends a message to an SQS queue. Automatically uses S3 Extended Client
     * for messages larger than 256KB.
     *
     * @param queueUrl  SQS queue URL
     * @param message   Message body
     * @return SendMessageResponse
     */
    public SendMessageResponse sendMessage(String queueUrl, String message) {
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        if (messageBytes.length <= SQS_MESSAGE_SIZE_LIMIT) {
            // Message fits in SQS, send directly
            return sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(message)
                    .build());
        } else {
            // Message too large, store in S3 and send pointer
            return sendMessageWithS3Pointer(queueUrl, messageBytes);
        }
    }

    /**
     * Sends a message with S3 pointer for large payloads.
     */
    private SendMessageResponse sendMessageWithS3Pointer(String queueUrl, byte[] messageBytes) {
        String messageId = java.util.UUID.randomUUID().toString();
        String s3Key = S3_POINTER_PREFIX + messageId;

        // Store the full payload in S3
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(s3Key)
                .build(),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(messageBytes));

        // Send pointer message to SQS
        String pointerMessage = buildPointerMessage(s3Key, messageBytes.length);

        return sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(pointerMessage)
                .messageAttributes(Map.of(
                        "s3Pointer", MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(s3Key)
                                .build(),
                        "originalSize", MessageAttributeValue.builder()
                                .dataType("Number")
                                .stringValue(String.valueOf(messageBytes.length))
                                .build()))
                .build());
    }

    /**
     * Receives a message from SQS. If it's an S3 pointer message, retrieves
     * the full payload from S3 automatically.
     *
     * @param queueUrl     SQS queue URL
     * @param maxMessages  Maximum number of messages to receive (1-10)
     * @return List of messages with full payloads
     */
    public java.util.List<ExtendedMessage> receiveMessages(String queueUrl, int maxMessages) {
        ReceiveMessageResponse response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(Math.min(maxMessages, 10))
                .messageAttributeNames("All")
                .waitTimeSeconds(20)
                .build());

        return response.messages().stream()
                .map(this::convertToExtendedMessage)
                .toList();
    }

    /**
     * Receives a single message from SQS with S3 pointer support.
     */
    public ExtendedMessage receiveMessage(String queueUrl) {
        ReceiveMessageResponse response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .messageAttributeNames("All")
                .waitTimeSeconds(20)
                .build());

        if (response.messages().isEmpty()) {
            return null;
        }

        return convertToExtendedMessage(response.messages().get(0));
    }

    /**
     * Converts an SQS message to an ExtendedMessage, resolving S3 pointers if needed.
     */
    private ExtendedMessage convertToExtendedMessage(Message message) {
        String messageBody = message.body();
        Map<String, MessageAttributeValue> messageAttributes = message.messageAttributes();

        // Check if this is an S3 pointer message
        MessageAttributeValue s3PointerAttr = messageAttributes.get("s3Pointer");
        if (s3PointerAttr != null && "String".equals(s3PointerAttr.dataType())) {
            String s3Key = s3PointerAttr.stringValue();
            String originalSizeStr = messageAttributes.getOrDefault(
                    "originalSize",
                    MessageAttributeValue.builder().dataType("Number").stringValue("0").build()
            ).stringValue();

            // Retrieve full payload from S3
            String fullMessage;
            try (ResponseInputStream<GetObjectResponse> s3Response = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(awsProperties.getS3Bucket())
                    .key(s3Key)
                    .build())) {
                byte[] fullPayload = s3Response.readAllBytes();
                fullMessage = new String(fullPayload, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read S3 object: " + s3Key, e);
            }

            return new ExtendedMessage(
                    message.messageId(),
                    fullMessage,
                    message.receiptHandle(),
                    message.md5OfBody(),
                    true, // isS3Pointer
                    s3Key,
                    Long.parseLong(originalSizeStr),
                    message.messageAttributes()
                            .entrySet().stream()
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> e.getValue().stringValue()
                                ))
            );
        } else {
            return new ExtendedMessage(
                    message.messageId(),
                    messageBody,
                    message.receiptHandle(),
                    message.md5OfBody(),
                    false, // isS3Pointer
                    null,
                    messageBody.getBytes(StandardCharsets.UTF_8).length,
                    message.messageAttributes()
                            .entrySet().stream()
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> e.getValue().stringValue()
                            ))
            );
        }
    }

    /**
     * Deletes a message from SQS. If it was an S3 pointer message, also deletes
     * the corresponding S3 object.
     *
     * @param queueUrl     SQS queue URL
     * @param message      Extended message to delete
     */
    public void deleteMessage(String queueUrl, ExtendedMessage message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());

        // Clean up S3 object if this was a pointer message
        if (message.isS3Pointer() && message.s3Key() != null) {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(awsProperties.getS3Bucket())
                    .key(message.s3Key())
                    .build());
        }
    }

    /**
     * Changes message visibility timeout. Useful for extending processing time
     * for large messages that take longer to process.
     *
     * @param queueUrl          SQS queue URL
     * @param receiptHandle     Message receipt handle
     * @param visibilityTimeout New visibility timeout in seconds
     */
    public void changeMessageVisibility(String queueUrl, String receiptHandle, int visibilityTimeout) {
        sqsClient.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .visibilityTimeout(visibilityTimeout)
                .build());
    }

    /**
     * Builds a JSON pointer message for S3 storage reference.
     */
    private String buildPointerMessage(String s3Key, long originalSize) {
        return "{\"s3Pointer\":\"" + s3Key + "\",\"originalSize\":" + originalSize + ",\"type\":\"S3_EXTENDED\"}";
    }

    /**
     * Extended message with S3 pointer support.
     */
    public record ExtendedMessage(
            String messageId,
            String body,
            String receiptHandle,
            String md5OfBody,
            boolean isS3Pointer,
            String s3Key,
            long originalSize,
            Map<String, String> attributes
    ) {}
}