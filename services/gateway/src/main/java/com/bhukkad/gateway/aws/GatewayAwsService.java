package com.bhukkad.gateway.aws;

import com.bhukkad.common.aws.AwsProperties;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Service implementations for Gateway Service.
 * Provides CloudWatch for API metrics, KMS for token encryption,
 * S3 for static assets.
 */
@Configuration
public class GatewayAwsService {

    private final CloudWatchClient cloudWatchClient;
    private final KmsClient kmsClient;
    private final S3Client s3Client;
    private final AwsProperties awsProperties;

    public GatewayAwsService(CloudWatchClient cloudWatchClient,
                             KmsClient kmsClient,
                             S3Client s3Client,
                             AwsProperties awsProperties) {
        this.cloudWatchClient = cloudWatchClient;
        this.kmsClient = kmsClient;
        this.s3Client = s3Client;
        this.awsProperties = awsProperties;
    }

    // ========== CloudWatch Operations - Gateway Metrics ==========

    public void recordApiRequest(String endpoint, String method, String statusCode) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Gateway")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("ApiRequests")
                .value(1.0)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("Endpoint")
                    .value(endpoint)
                    .build())
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("Method")
                    .value(method)
                    .build())
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("StatusCode")
                    .value(statusCode)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }

    public void recordApiLatency(String endpoint, double latencyMs) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Gateway")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("ApiLatency")
                .value(latencyMs)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.MILLISECONDS)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("Endpoint")
                    .value(endpoint)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }

    public void recordErrorRate(String endpoint, String statusCode) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Gateway")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("ApiErrors")
                .value(1.0)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("Endpoint")
                    .value(endpoint)
                    .build())
                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                    .name("StatusCode")
                    .value(statusCode)
                    .build())
                .timestamp(Instant.now())
                .build())
            .build());
    }

    public void recordActiveConnections(int count) {
        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
            .namespace("Bhukkad/Gateway")
            .metricData(software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                .metricName("ActiveConnections")
                .value((double) count)
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .timestamp(Instant.now())
                .build())
            .build());
    }

    // ========== KMS Operations - Token Encryption ==========

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

    public byte[] encryptApiKey(String apiKey) {
        return kmsClient.encrypt(software.amazon.awssdk.services.kms.model.EncryptRequest.builder()
            .keyId(awsProperties.getKmsKeyAlias())
            .plaintext(ByteBuffer.wrap(apiKey.getBytes(StandardCharsets.UTF_8)))
            .build())
            .ciphertextBlob()
            .asBytes();
    }

    public byte[] decryptApiKey(byte[] ciphertext) {
        return kmsClient.decrypt(software.amazon.awssdk.services.kms.model.DecryptRequest.builder()
            .ciphertextBlob(ByteBuffer.wrap(ciphertext))
            .build())
            .plaintext()
            .asBytes();
    }

    // ========== S3 Operations - Static Assets ==========

    public void uploadStaticAsset(String assetKey, byte[] assetData, String contentType) {
        s3Client.putObject(PutObjectRequest.builder()
            .bucket(awsProperties.getS3Bucket())
            .key(assetKey)
            .contentType(contentType)
            .build(),
            RequestBody.fromBytes(assetData));
    }

    public byte[] getStaticAsset(String assetKey) {
        return s3Client.getObject(GetObjectRequest.builder()
            .bucket(awsProperties.getS3Bucket())
            .key(assetKey)
            .build())
            .readAllBytes();
    }

    public void deleteStaticAsset(String assetKey) {
        s3Client.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
            .bucket(awsProperties.getS3Bucket())
            .key(assetKey)
            .build());
    }

    public void uploadCssFile(String filename, byte[] cssContent) {
        uploadStaticAsset("static/css/" + filename, cssContent, "text/css");
    }

    public void uploadJsFile(String filename, byte[] jsContent) {
        uploadStaticAsset("static/js/" + filename, jsContent, "application/javascript");
    }

    public void uploadImage(String filename, byte[] imageData, String contentType) {
        uploadStaticAsset("static/images/" + filename, imageData, contentType);
    }

    public byte[] getStaticAsset(String assetType, String filename) {
        return getStaticAsset("static/" + assetType + "/" + filename);
    }
}
