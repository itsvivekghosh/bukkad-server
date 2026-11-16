package com.bhukkad.identity.aws;

import com.bhukkad.common.aws.AwsProperties;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.core.sync.RequestBody;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Identity Service AWS integrations.
 * <p>
 * Provides S3 for customer profile images, Secrets Manager for JWT/API secrets,
 * and KMS for password/token encryption.
 */
@Configuration(proxyBeanMethods = false)
public class IdentityAwsService {

    private final S3Client s3Client;
    private final SecretsManagerClient secretsManagerClient;
    private final KmsClient kmsClient;
    private final AwsProperties awsProperties;

    public IdentityAwsService(S3Client s3Client,
                              SecretsManagerClient secretsManagerClient,
                              KmsClient kmsClient,
                              AwsProperties awsProperties) {
        this.s3Client = s3Client;
        this.secretsManagerClient = secretsManagerClient;
        this.kmsClient = kmsClient;
        this.awsProperties = awsProperties;
    }

    // S3 - Customer Profile Images
    public void uploadProfileImage(String customerId, byte[] imageData, String contentType) {
        String key = "customer-avatars/" + customerId + ".jpg";
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .contentType(contentType)
                .build(),
                RequestBody.fromBytes(imageData));
    }

    public byte[] getProfileImage(String customerId) throws IOException {
        String key = "customer-avatars/" + customerId + ".jpg";
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .build())
                .readAllBytes();
    }

    public void deleteProfileImage(String customerId) {
        String key = "customer-avatars/" + customerId + ".jpg";
        s3Client.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .build());
    }

    // Secrets Manager - JWT & API Secrets
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

    // KMS - Password & Token Encryption
    public byte[] encryptPassword(String plaintextPassword) {
        return kmsClient.encrypt(EncryptRequest.builder()
                .keyId(awsProperties.getKmsKeyAlias())
                .plaintext(SdkBytes.fromByteBuffer(ByteBuffer.wrap(plaintextPassword.getBytes(StandardCharsets.UTF_8))))
                .build())
                .ciphertextBlob()
                .asByteArray();
    }

    public byte[] decryptPassword(byte[] ciphertext) {
        return kmsClient.decrypt(DecryptRequest.builder()
                .ciphertextBlob(SdkBytes.fromByteBuffer(ByteBuffer.wrap(ciphertext)))
                .build())
                .plaintext()
                .asByteArray();
    }
}