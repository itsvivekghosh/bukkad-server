package com.bhukkad.admin.aws;

import com.bhukkad.common.aws.AwsProperties;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.core.sync.RequestBody;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Service implementations for Admin Service.
 * Provides Secrets Manager for admin credentials, CloudWatch for admin metrics,
 * KMS for admin data encryption.
 */
@Configuration
public class AdminAwsService {

    private final SecretsManagerClient secretsManagerClient;
    private final CloudWatchClient cloudWatchClient;
    private final KmsClient kmsClient;
    private final AwsProperties awsProperties;

    public AdminAwsService(SecretsManagerClient secretsManagerClient,
                           CloudWatchClient cloudWatchClient,
                           KmsClient kmsClient,
                           AwsProperties awsProperties) {
        this.secretsManagerClient = secretsManagerClient;
        this.cloudWatchClient = cloudWatchClient;
        this.kmsClient = kmsClient;
        this.awsProperties = awsProperties;
    }

    // ========== Secrets Manager Operations - Admin Credentials ==========

    public void storeAdminSecret(String secretName, String secretValue) {
        secretsManagerClient.putSecretValue(software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest.builder()
            .secretId(secretName)
            .secretString(secretValue)
            .build());
    }

    public String getSecret(String secretName) {
        return secretsManagerClient.getSecretValue(software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest.builder()
            .secretId(secretName)
            .build())
            .secretString();
    }

    public void rotateAdminPassword(String secretName, String newPassword) {
        secretsManagerClient.putSecretValue(software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest.builder()
            .secretId(secretName)
            .secretString(newPassword)
            .build());
    }

    public void storeApiKey(String serviceName, String apiKey) {
        secretsManagerClient.putSecretValue(software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest.builder()
            .secretId("bhukkad/admin/" + serviceName)
            .secretString(apiKey)
            .build());
    }

    public String getApiKey(String serviceName) {
        return secretsManagerClient.getSecretValue(software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest.builder()
            .secretId("bhukkad/admin/" + serviceName)
            .build())
            .secretString();
    }

    // ========== KMS Operations - Admin Data Encryption ==========

    public byte[] encryptAdminData(String plaintextData) {
        return kmsClient.encrypt(software.amazon.awssdk.services.kms.model.EncryptRequest.builder()
            .keyId(awsProperties.getKmsKeyAlias())
            .plaintext(ByteBuffer.wrap(plaintextData.getBytes(StandardCharsets.UTF_8)))
            .build())
            .ciphertextBlob()
            .asBytes();
    }

    public byte[] decryptAdminData(byte[] ciphertext) {
        return kmsClient.decrypt(software.amazon.awssdk.services.kms.model.DecryptRequest.builder()
            .ciphertextBlob(ByteBuffer.wrap(ciphertext))
            .build())
            .plaintext()
            .asBytes();
    }

    public byte[] encryptAdminToken(String adminToken) {
        return kmsClient.encrypt(software.amazon.awssdk.services.kms.model.EncryptRequest.builder()
            .keyId(awsProperties.getKmsKeyAlias())
            .plaintext(ByteBuffer.wrap(adminToken.getBytes(StandardCharsets.UTF_8)))
            .build())
            .ciphertextBlob()
            .asBytes();
    }

    public byte[] decryptAdminToken(byte[] ciphertext) {
        return kmsClient.decrypt(software.amazon.awssdk.services.kms.model.DecryptRequest.builder()
            .ciphertextBlob(ByteBuffer.wrap(ciphertext))
            .build())
            .plaintext()
            .asBytes();
    }

    // ========== CloudWatch Operations - Admin Metrics ==========

    public void recordAdminAction(String action, String adminId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Admin")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("AdminActionPerformed")
                .value(1.0)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("AdminId")
                    .value(adminId)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }

    public void recordRestaurantApproval(String restaurantId, String adminId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Admin")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("RestaurantApproved")
                .value(1.0)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("RestaurantId")
                    .value(restaurantId)
                    .build())
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("AdminId")
                    .value(adminId)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }

    public void recordSettlementProcessed(String settlementId, Double amount) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Admin")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("SettlementProcessed")
                .value(amount)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.CURRENCY_USD)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("SettlementId")
                    .value(settlementId)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }

    public void recordSupportTicketCreated(String ticketId, String priority) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Admin")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("SupportTicketCreated")
                .value(1.0)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("TicketId")
                    .value(ticketId)
                    .build())
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("Priority")
                    .value(priority)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }
}
