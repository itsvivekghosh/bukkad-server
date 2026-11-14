package com.bhukkad.admin.aws;

import com.bhukkad.common.aws.AwsProperties;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.SdkBytes;
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
 * Admin Service AWS integrations.
 * <p>
 * Provides Secrets Manager for admin credentials/API keys,
 * KMS for admin data encryption, and CloudWatch for admin metrics.
 */
@Configuration(proxyBeanMethods = false)
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

    // Secrets Manager - Admin Credentials
    public void storeAdminSecret(String secretName, String secretValue) {
        secretsManagerClient.putSecretValue(PutSecretValueRequest.builder()
                .secretId(secretName)
                .secretString(secretValue)
                .build());
    }

    public String getSecret(String secretName) {
        return secretsManagerClient.getSecretValue(GetSecretValueRequest.builder()
                .secretId(secretName)
                .build())
                .secretString();
    }

    public void rotateAdminPassword(String secretName, String newPassword) {
        secretsManagerClient.putSecretValue(PutSecretValueRequest.builder()
                .secretId(secretName)
                .secretString(newPassword)
                .build());
    }

    public void storeApiKey(String serviceName, String apiKey) {
        secretsManagerClient.putSecretValue(PutSecretValueRequest.builder()
                .secretId(awsProperties.getSecretsManagerPrefix() + "admin/" + serviceName)
                .secretString(apiKey)
                .build());
    }

    public String getApiKey(String serviceName) {
        return secretsManagerClient.getSecretValue(GetSecretValueRequest.builder()
                .secretId(awsProperties.getSecretsManagerPrefix() + "admin/" + serviceName)
                .build())
                .secretString();
    }

    // KMS - Admin Data Encryption
    public byte[] encryptAdminData(String plaintextData) {
        return kmsClient.encrypt(EncryptRequest.builder()
                .keyId(awsProperties.getKmsKeyAlias())
                .plaintext(SdkBytes.fromByteBuffer(ByteBuffer.wrap(plaintextData.getBytes(StandardCharsets.UTF_8))))
                .build())
                .ciphertextBlob()
                .asByteArray();
    }

    public byte[] decryptAdminData(byte[] ciphertext) {
        return kmsClient.decrypt(DecryptRequest.builder()
                .ciphertextBlob(SdkBytes.fromByteBuffer(ByteBuffer.wrap(ciphertext)))
                .build())
                .plaintext()
                .asByteArray();
    }

    // CloudWatch - Admin Metrics
    public void recordAdminAction(String action, String adminId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                .namespace("Bhukkad/Admin")
                .metricData(MetricDatum.builder()
                        .metricName("AdminActionPerformed")
                        .value(1.0)
                        .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                        .dimensions(Dimension.builder()
                                .name("AdminId")
                                .value(adminId)
                                .build())
                        .dimensions(Dimension.builder()
                                .name("Action")
                                .value(action)
                                .build())
                        .timestamp(Instant.now())
                        .build())
                .build());
    }

    public void recordRestaurantApproval(String restaurantId, String adminId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                .namespace("Bhukkad/Admin")
                .metricData(MetricDatum.builder()
                        .metricName("RestaurantApproved")
                        .value(1.0)
                        .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                        .dimensions(Dimension.builder()
                                .name("RestaurantId")
                                .value(restaurantId)
                                .build())
                        .dimensions(Dimension.builder()
                                .name("AdminId")
                                .value(adminId)
                                .build())
                        .timestamp(Instant.now())
                        .build())
                .build());
    }

    public void recordTicketResolved(String ticketId, String adminId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                .namespace("Bhukkad/Admin")
                .metricData(MetricDatum.builder()
                        .metricName("TicketResolved")
                        .value(1.0)
                        .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                        .dimensions(Dimension.builder()
                                .name("TicketId")
                                .value(ticketId)
                                .build())
                        .dimensions(Dimension.builder()
                                .name("AdminId")
                                .value(adminId)
                                .build())
                        .timestamp(Instant.now())
                        .build())
                .build());
    }
}