package com.bhukkad.identity.aws;

import com.bhukkad.common.aws.AwsProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.core.sync.RequestBody;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Service implementations for Identity Service.
 * Provides S3 for profile images, Secrets Manager for JWT secrets,
 * KMS for password encryption/decryption.
 */
@Configuration
public class IdentityAwsService {

    private final S3Client s3Client;
    private final SecretsManagerClient secretsManagerClient;
    private final KmsClient kmsClient;
    private final ElastiCacheClient elasticacheClient;
    private final CloudWatchClient cloudWatchClient;
    private final AwsProperties awsProperties;

    public IdentityAwsService(S3Client s3Client,
                              SecretsManagerClient secretsManagerClient,
                              KmsClient kmsClient,
                              ElastiCacheClient elasticacheClient,
                              CloudWatchClient cloudWatchClient,
                              AwsProperties awsProperties) {
        this.s3Client = s3Client;
        this.secretsManagerClient = secretsManagerClient;
        this.kmsClient = kmsClient;
        this.elasticacheClient = elasticacheClient;
        this.cloudWatchClient = cloudWatchClient;
        this.awsProperties = awsProperties;
    }

    // ========== S3 Operations - Profile Images ==========

    public void uploadProfileImage(String customerId, byte[] imageData, String contentType) {
        String key = "customer-avatars/" + customerId + ".jpg";
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .contentType(contentType)
                .build(),
            RequestBody.fromBytes(imageData));
    }

    public byte[] getProfileImage(String customerId) {
        String key = "customer-avatars/" + customerId + ".jpg";
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .build())
            .readAllBytes();
    }

    public void deleteProfileImage(String customerId) {
        s3Client.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key("customer-avatars/" + customerId + ".jpg")
                .build());
    }

    // ========== Secrets Manager - JWT & API Secrets ==========

    public String getSecret(String secretName) {
        return secretsManagerClient.getSecretValue(
                software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build())
            .secretString();
    }

    public void storeSecret(String secretName, String secretValue) {
        secretsManagerClient.putSecretValue(
            software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest.builder()
                .secretId(secretName)
                .secretString(secretValue)
                .build());
    }

    // ========== KMS - Password & Token Encryption ==========

    public byte[] encryptPassword(String plaintextPassword) {
        return kmsClient.encrypt(software.amazon.awssdk.services.kms.model.EncryptRequest.builder()
            .keyId(awsProperties.getKmsKeyAlias())
            .plaintext(ByteBuffer.wrap(plaintextPassword.getBytes(StandardCharsets.UTF_8)))
            .build())
            .ciphertextBlob()
            .asBytes();
    }

    public byte[] decryptPassword(byte[] ciphertext) {
        return kmsClient.decrypt(software.amazon.awssdk.services.kms.model.DecryptRequest.builder()
            .ciphertextBlob(ByteBuffer.wrap(ciphertext))
            .build())
            .plaintext()
            .asBytes();
    }

    public byte[] encryptJwtToken(String jwtToken) {
        return kmsClient.encrypt(software.amazon.awssdk.services.kms.model.EncryptRequest.builder()
            .keyId(awsProperties.getKmsKeyAlias())
            .plaintext(ByteBuffer.wrap(jwtToken.getBytes(StandardCharsets.UTF_8)))
            .build())
            .ciphertextBlob()
            .asBytes();
    }

    public byte[] decryptJwtToken(byte[] ciphertext) {
        return kmsClient.decrypt(software.amazon.awssdk.services.kms.model.DecryptRequest.builder()
            .ciphertextBlob(ByteBuffer.wrap(ciphertext))
            .build())
            .plaintext()
            .asBytes();
    }

    // ========== ElastiCache - Customer Session Storage ==========

    public void saveCustomerSession(String customerId, String sessionData, int ttlSeconds) {
        // Note: ElastiCache client is for management operations.
        // For actual data operations, use a Redis client connecting to ElastiCache endpoint.
        // This method demonstrates the pattern using ElastiCache client for management.
        cloudWatchClient.putMetricData(software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest.builder()
            .namespace("Bhukkad/Identity")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("SessionStored")
                .value(1.0)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("CustomerId")
                    .value(customerId)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }

    public void invalidateSession(String customerId) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Identity")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("SessionInvalidated")
                .value(1.0)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("CustomerId")
                    .value(customerId)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }
}
