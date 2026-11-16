package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;
import software.amazon.awssdk.core.SdkBytes;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Common KMS service providing advanced key management features:
 * - Multi-Region keys for disaster recovery
 * - Asymmetric keys for JWT RS256 signing
 * - Key rotation and alias management
 * - Encryption/decryption with caching
 */
@Service
public class KmsService {

    private final KmsClient kmsClient;
    private final AwsProperties awsProperties;
    private final Map<String, PublicKey> publicKeyCache = new ConcurrentHashMap<>();
    private final Map<String, String> publicKeyPemCache = new ConcurrentHashMap<>();

    public KmsService(KmsClient kmsClient, AwsProperties awsProperties) {
        this.kmsClient = kmsClient;
        this.awsProperties = awsProperties;
    }

    // ==================== Symmetric Encryption (Existing) ====================

    public byte[] encrypt(String plaintext) {
        return encrypt(awsProperties.getKmsKeyAlias(), plaintext);
    }

    public byte[] encrypt(String keyId, String plaintext) {
        return kmsClient.encrypt(EncryptRequest.builder()
                .keyId(keyId)
                .plaintext(SdkBytes.fromByteBuffer(ByteBuffer.wrap(plaintext.getBytes(StandardCharsets.UTF_8))))
                .build())
                .ciphertextBlob()
                .asByteArray();
    }

    public byte[] encryptBytes(String keyId, byte[] plaintext) {
        return kmsClient.encrypt(EncryptRequest.builder()
                .keyId(keyId)
                .plaintext(SdkBytes.fromByteArray(plaintext))
                .build())
                .ciphertextBlob()
                .asByteArray();
    }

    public String decrypt(byte[] ciphertext) {
        return decrypt(awsProperties.getKmsKeyAlias(), ciphertext);
    }

    public String decrypt(String keyId, byte[] ciphertext) {
        return kmsClient.decrypt(DecryptRequest.builder()
                .keyId(keyId)
                .ciphertextBlob(SdkBytes.fromByteArray(ciphertext))
                .build())
                .plaintext()
                .asUtf8String();
    }

    public byte[] decryptBytes(String keyId, byte[] ciphertext) {
        return kmsClient.decrypt(DecryptRequest.builder()
                .keyId(keyId)
                .ciphertextBlob(SdkBytes.fromByteArray(ciphertext))
                .build())
                .plaintext()
                .asByteArray();
    }

    // ==================== Asymmetric Keys (RS256 for JWT) ====================

    /**
     * Creates an asymmetric RSA key pair for JWT signing (RS256).
     * The private key stays in KMS, public key can be distributed.
     */
    public String createAsymmetricSigningKey(String alias, String description) {
        CreateKeyRequest request = CreateKeyRequest.builder()
                .description(description)
                .keyUsage(KeyUsageType.SIGN_VERIFY)
                .customerMasterKeySpec(CustomerMasterKeySpec.RSA_2048)
                .origin(OriginType.AWS_KMS)
                .multiRegion(false)
                .build();

        CreateKeyResponse response = kmsClient.createKey(request);
        String keyId = response.keyMetadata().keyId();

        // Create alias
        kmsClient.createAlias(CreateAliasRequest.builder()
                .aliasName("alias/" + alias)
                .targetKeyId(keyId)
                .build());

        return keyId;
    }

    /**
     * Creates a multi-region asymmetric key for disaster recovery.
     * The key is replicated across regions automatically.
     */
    public String createMultiRegionAsymmetricKey(String alias, String description, List<String> replicaRegions) {
        CreateKeyRequest request = CreateKeyRequest.builder()
                .description(description)
                .keyUsage(KeyUsageType.SIGN_VERIFY)
                .customerMasterKeySpec(CustomerMasterKeySpec.RSA_2048)
                .origin(OriginType.AWS_KMS)
                .multiRegion(true)
                .build();

        CreateKeyResponse response = kmsClient.createKey(request);
        String keyId = response.keyMetadata().keyId();

        // Create alias in primary region
        kmsClient.createAlias(CreateAliasRequest.builder()
                .aliasName("alias/" + alias)
                .targetKeyId(keyId)
                .build());

        // Replicate to other regions
        for (String region : replicaRegions) {
            if (!region.equals(awsProperties.getRegion())) {
                kmsClient.replicateKey(ReplicateKeyRequest.builder()
                        .keyId(keyId)
                        .replicaRegion(region)
                        .description("Multi-region replica for " + alias)
                        .build());
            }
        }

        return keyId;
    }

    /**
     * Signs data using an asymmetric key (for JWT RS256).
     * Uses RSASSA_PKCS1_V1_5_SHA_256 algorithm.
     */
    public byte[] signAsymmetric(String keyId, byte[] message) {
        SignRequest request = SignRequest.builder()
                .keyId(keyId)
                .message(SdkBytes.fromByteArray(message))
                .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256)
                .messageType(MessageType.RAW)
                .build();

        return kmsClient.sign(request).signature().asByteArray();
    }

    /**
     * Verifies a signature using an asymmetric key.
     */
    public boolean verifyAsymmetric(String keyId, byte[] message, byte[] signature) {
        VerifyRequest request = VerifyRequest.builder()
                .keyId(keyId)
                .message(SdkBytes.fromByteArray(message))
                .signature(SdkBytes.fromByteArray(signature))
                .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256)
                .messageType(MessageType.RAW)
                .build();

        return kmsClient.verify(request).signatureValid();
    }

    /**
     * Gets the public key in PEM format for JWT verification.
     * Caches the public key for performance.
     */
    public String getPublicKeyPem(String keyId) {
        return publicKeyPemCache.computeIfAbsent(keyId, k -> {
            PublicKey publicKey = getPublicKey(k);
            String base64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
            
            StringBuilder pem = new StringBuilder();
            pem.append("-----BEGIN PUBLIC KEY-----\n");
            for (int i = 0; i < base64.length(); i += 64) {
                pem.append(base64.substring(i, Math.min(i + 64, base64.length()))).append("\n");
            }
            pem.append("-----END PUBLIC KEY-----\n");
            return pem.toString();
        });
    }

    /**
     * Gets the public key as a Java PublicKey object.
     */
    public PublicKey getPublicKey(String keyId) {
        try {
            GetPublicKeyResponse response = kmsClient.getPublicKey(GetPublicKeyRequest.builder()
                    .keyId(keyId)
                    .build());
            return KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(response.publicKey().asByteArray()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to get public key for " + keyId, e);
        }
    }

    // ==================== Multi-Region Key Management ====================

    /**
     * Creates a multi-region symmetric key for encryption.
     */
    public String createMultiRegionSymmetricKey(String alias, String description, List<String> replicaRegions) {
        CreateKeyRequest request = CreateKeyRequest.builder()
                .description(description)
                .keyUsage(KeyUsageType.ENCRYPT_DECRYPT)
                .customerMasterKeySpec(CustomerMasterKeySpec.SYMMETRIC_DEFAULT)
                .origin(OriginType.AWS_KMS)
                .multiRegion(true)
                .build();

        CreateKeyResponse response = kmsClient.createKey(request);
        String keyId = response.keyMetadata().keyId();

        kmsClient.createAlias(CreateAliasRequest.builder()
                .aliasName("alias/" + alias)
                .targetKeyId(keyId)
                .build());

        for (String region : replicaRegions) {
            if (!region.equals(awsProperties.getRegion())) {
                kmsClient.replicateKey(ReplicateKeyRequest.builder()
                        .keyId(keyId)
                        .replicaRegion(region)
                        .description("Multi-region replica for " + alias)
                        .build());
            }
        }

        return keyId;
    }

    /**
     * Lists all multi-region keys.
     */
    public List<KeyMetadata> listMultiRegionKeys() {
        ListKeysResponse response = kmsClient.listKeys();
        return response.keys().stream()
                .map(key -> kmsClient.describeKey(DescribeKeyRequest.builder().keyId(key.keyId()).build()).keyMetadata())
                .filter(KeyMetadata::multiRegion)
                .toList();
    }

    /**
     * Updates the primary region of a multi-region key.
     */
    public void updatePrimaryRegion(String keyId, String newPrimaryRegion) {
        kmsClient.updatePrimaryRegion(UpdatePrimaryRegionRequest.builder()
                .keyId(keyId)
                .primaryRegion(newPrimaryRegion)
                .build());
    }

    // ==================== Key Rotation ====================

    /**
     * Enables automatic annual key rotation.
     */
    public void enableKeyRotation(String keyId) {
        kmsClient.enableKeyRotation(EnableKeyRotationRequest.builder()
                .keyId(keyId)
                .build());
    }

    /**
     * Disables automatic key rotation.
     */
    public void disableKeyRotation(String keyId) {
        kmsClient.disableKeyRotation(DisableKeyRotationRequest.builder()
                .keyId(keyId)
                .build());
    }

    /**
     * Checks if key rotation is enabled.
     */
    public boolean isKeyRotationEnabled(String keyId) {
        return kmsClient.getKeyRotationStatus(GetKeyRotationStatusRequest.builder()
                .keyId(keyId)
                .build()).keyRotationEnabled();
    }

    // ==================== Key Alias Management ====================

    public void createAlias(String alias, String keyId) {
        kmsClient.createAlias(CreateAliasRequest.builder()
                .aliasName("alias/" + alias)
                .targetKeyId(keyId)
                .build());
    }

    public void updateAlias(String alias, String newKeyId) {
        kmsClient.updateAlias(UpdateAliasRequest.builder()
                .aliasName("alias/" + alias)
                .targetKeyId(newKeyId)
                .build());
    }

    public void deleteAlias(String alias) {
        kmsClient.deleteAlias(DeleteAliasRequest.builder()
                .aliasName("alias/" + alias)
                .build());
    }

    public String getKeyIdFromAlias(String alias) {
        try {
            return kmsClient.describeKey(DescribeKeyRequest.builder()
                    .keyId("alias/" + alias)
                    .build()).keyMetadata().keyId();
        } catch (KmsException e) {
            return null;
        }
    }

    // ==================== Key Policies ====================

    /**
     * Sets a key policy for least-privilege access.
     */
    public void setKeyPolicy(String keyId, String policyName, String policyJson) {
        kmsClient.putKeyPolicy(PutKeyPolicyRequest.builder()
                .keyId(keyId)
                .policyName(policyName)
                .policy(policyJson)
                .build());
    }

    /**
     * Gets the current key policy.
     */
    public String getKeyPolicy(String keyId, String policyName) {
        return kmsClient.getKeyPolicy(GetKeyPolicyRequest.builder()
                .keyId(keyId)
                .policyName(policyName)
                .build()).policy();
    }

    // ==================== Key Deletion ====================

    /**
     * Schedules key deletion (7-30 days waiting period).
     */
    public void scheduleKeyDeletion(String keyId, int pendingWindowDays) {
        kmsClient.scheduleKeyDeletion(ScheduleKeyDeletionRequest.builder()
                .keyId(keyId)
                .pendingWindowInDays(pendingWindowDays)
                .build());
    }

    /**
     * Cancels scheduled key deletion.
     */
    public void cancelKeyDeletion(String keyId) {
        kmsClient.cancelKeyDeletion(CancelKeyDeletionRequest.builder()
                .keyId(keyId)
                .build());
    }
}