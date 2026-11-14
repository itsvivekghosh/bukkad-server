package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedDeleteObjectRequest;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.FileUpload;
import software.amazon.awssdk.transfer.s3.model.FileDownload;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest;
import software.amazon.awssdk.transfer.s3.progress.TransferListener;
import software.amazon.awssdk.transfer.s3.progress.TransferProgressSnapshot;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Common S3 service providing high-level operations using S3 Transfer Manager
 * for multipart/parallel uploads and presigned URL generation for direct client uploads.
 */
@Service
public class S3Service {

    private final S3Client s3Client;
    private final S3AsyncClient s3AsyncClient;
    private final S3TransferManager transferManager;
    private final S3Presigner presigner;
    private final AwsProperties awsProperties;

    public S3Service(S3Client s3Client,
                     S3AsyncClient s3AsyncClient,
                     S3TransferManager transferManager,
                     AwsProperties awsProperties) {
        this.s3Client = s3Client;
        this.s3AsyncClient = s3AsyncClient;
        this.transferManager = transferManager;
        this.presigner = S3Presigner.builder()
                .region(software.amazon.awssdk.regions.Region.of(awsProperties.getRegion()))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                                awsProperties.getAccessKey(), awsProperties.getSecretKey())))
                .endpointOverride(java.net.URI.create(awsProperties.getEndpoint()))
                .build();
        this.awsProperties = awsProperties;
    }

    // ==================== Presigned URLs ====================

    /**
     * Generates a presigned PUT URL for direct client upload.
     * Client can upload directly to S3 without going through the service.
     *
     * @param key          Object key in S3
     * @param contentType  MIME type of the content
     * @param expiration   URL expiration duration
     * @return Presigned URL and signed headers
     */
    public PresignedPutObjectRequest generatePresignedPutUrl(String key, String contentType, Duration expiration) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .contentType(contentType)
                .build();

        return presigner.presignPutObject(builder -> builder
                .putObjectRequest(putRequest)
                .signatureDuration(expiration));
    }

    /**
     * Generates a presigned GET URL for direct client download.
     *
     * @param key        Object key in S3
     * @param expiration URL expiration duration
     * @return Presigned URL
     */
    public PresignedGetObjectRequest generatePresignedGetUrl(String key, Duration expiration) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .build();

        return presigner.presignGetObject(builder -> builder
                .getObjectRequest(getRequest)
                .signatureDuration(expiration));
    }

    /**
     * Generates a presigned DELETE URL.
     *
     * @param key        Object key in S3
     * @param expiration URL expiration duration
     * @return Presigned URL
     */
    public PresignedDeleteObjectRequest generatePresignedDeleteUrl(String key, Duration expiration) {
        DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .build();

        return presigner.presignDeleteObject(builder -> builder
                .deleteObjectRequest(deleteRequest)
                .signatureDuration(expiration));
    }

    // ==================== Transfer Manager Operations ====================

    /**
     * Uploads a file using Transfer Manager (multipart/parallel for large files).
     *
     * @param key         Object key in S3
     * @param file        File to upload
     * @param contentType MIME type
     * @param progressListener Optional progress listener receiving TransferProgressSnapshot
     * @return CompletableFuture completing when upload finishes
     */
    public CompletableFuture<CompletedUpload> uploadFile(String key, File file, String contentType,
                                                          Consumer<TransferProgressSnapshot> progressListener) {
        TransferListener listener = progressListener != null ? new TransferListener() {
            @Override
            public void bytesTransferred(TransferListener.Context.BytesTransferred context) {
                progressListener.accept(context.progressSnapshot());
            }
        } : null;

        UploadFileRequest.Builder builder = UploadFileRequest.builder()
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(awsProperties.getS3Bucket())
                        .key(key)
                        .contentType(contentType)
                        .build())
                .source(file.toPath());

        if (listener != null) {
            builder.addTransferListener(listener);
        }

        FileUpload upload = transferManager.uploadFile(builder.build());

        return upload.completionFuture()
                .thenApply(response -> new CompletedUpload(key, response.response().eTag(), Instant.now()));
    }

    /**
     * Uploads data from InputStream using Transfer Manager.
     *
     * @param key         Object key in S3
     * @param inputStream Input stream
     * @param contentLength Content length (required for multipart)
     * @param contentType MIME type
     * @return CompletableFuture completing when upload finishes
     */
    public CompletableFuture<CompletedUpload> uploadStream(String key, InputStream inputStream,
                                                            long contentLength, String contentType) {
        // For stream uploads, use the low-level upload API
        software.amazon.awssdk.transfer.s3.model.Upload upload = transferManager.upload(u -> u
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(awsProperties.getS3Bucket())
                        .key(key)
                        .contentType(contentType)
                        .contentLength(contentLength)
                        .build())
                .requestBody(AsyncRequestBody.fromInputStream(inputStream, contentLength)));

        return upload.completionFuture()
                .thenApply(response -> new CompletedUpload(key, response.response().eTag(), Instant.now()));
    }

    /**
     * Downloads a file using Transfer Manager.
     *
     * @param key        Object key in S3
     * @param destination Destination file
     * @param progressListener Optional progress listener receiving TransferProgressSnapshot
     * @return CompletableFuture completing when download finishes
     */
    public CompletableFuture<CompletedDownload> downloadFile(String key, File destination,
                                                              Consumer<TransferProgressSnapshot> progressListener) {
        TransferListener listener = progressListener != null ? new TransferListener() {
            @Override
            public void bytesTransferred(TransferListener.Context.BytesTransferred context) {
                progressListener.accept(context.progressSnapshot());
            }
        } : null;

        DownloadFileRequest.Builder builder = DownloadFileRequest.builder()
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(awsProperties.getS3Bucket())
                        .key(key)
                        .build())
                .destination(destination.toPath());

        if (listener != null) {
            builder.addTransferListener(listener);
        }

        FileDownload download = transferManager.downloadFile(builder.build());

        return download.completionFuture()
                .thenApply(response -> new CompletedDownload(key, destination.length(), Instant.now()));
    }

    /**
     * Downloads object as byte array (for small objects).
     */
    public byte[] downloadBytes(String key) throws IOException {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .build();

        return s3Client.getObject(getRequest).readAllBytes();
    }

    // ==================== Basic Operations ====================

    public void deleteObject(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .build());
    }

    public boolean objectExists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(awsProperties.getS3Bucket())
                    .key(key)
                    .build());
            return true;
        } catch (S3Exception e) {
            return false;
        }
    }

    public HeadObjectResponse getObjectMetadata(String key) {
        return s3Client.headObject(HeadObjectRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .key(key)
                .build());
    }

    // ==================== S3 Express One Zone ====================

    /**
     * Uploads to S3 Express One Zone directory bucket for ultra-low latency.
     * <p>
     * S3 Express One Zone provides 10x lower latency for frequently accessed data.
     * Requires a directory bucket in a specific availability zone.
     */
    public CompletableFuture<CompletedUpload> uploadToExpressZone(String key, File file, String contentType,
                                                                   Consumer<software.amazon.awssdk.transfer.s3.progress.TransferProgressSnapshot> progressListener) {
        String expressBucket = awsProperties.getS3ExpressBucket();
        if (expressBucket == null || expressBucket.isEmpty()) {
            throw new IllegalStateException("S3 Express One Zone bucket not configured");
        }

        TransferListener listener = progressListener != null ? new TransferListener() {
            @Override
            public void bytesTransferred(TransferListener.Context.BytesTransferred context) {
                progressListener.accept(context.progressSnapshot());
            }
        } : null;

        UploadFileRequest.Builder builder = UploadFileRequest.builder()
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(expressBucket)
                        .key(key)
                        .contentType(contentType)
                        .storageClass(StorageClass.EXPRESS_ONEZONE)
                        .build())
                .source(file.toPath());

        if (listener != null) {
            builder.addTransferListener(listener);
        }

        FileUpload upload = transferManager.uploadFile(builder.build());

        return upload.completionFuture()
                .thenApply(response -> new CompletedUpload(key, response.response().eTag(), Instant.now()));
    }

    /**
     * Generates a presigned URL for S3 Express One Zone bucket.
     */
    public PresignedPutObjectRequest generateExpressPresignedPutUrl(String key, String contentType, Duration expiration) {
        String expressBucket = awsProperties.getS3ExpressBucket();
        if (expressBucket == null || expressBucket.isEmpty()) {
            throw new IllegalStateException("S3 Express One Zone bucket not configured");
        }

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(expressBucket)
                .key(key)
                .contentType(contentType)
                .storageClass(StorageClass.EXPRESS_ONEZONE)
                .build();

        return presigner.presignPutObject(builder -> builder
                .putObjectRequest(putRequest)
                .signatureDuration(expiration));
    }

    /**
     * Creates an S3 Express One Zone directory bucket.
     * <p>
     * Must be called during infrastructure setup.
     */
    public void createExpressBucket(String availabilityZoneId) {
        String expressBucket = awsProperties.getS3ExpressBucket();
        if (expressBucket == null || expressBucket.isEmpty()) {
            throw new IllegalStateException("S3 Express One Zone bucket name not configured");
        }

        // Directory bucket names must follow format: bucket-name--zone-id--x-s3
        String directoryBucketName = expressBucket + "--" + availabilityZoneId + "--x-s3";

        s3Client.createBucket(CreateBucketRequest.builder()
                .bucket(directoryBucketName)
                .createBucketConfiguration(CreateBucketConfiguration.builder()
                        .locationConstraint(awsProperties.getRegion())
                        .bucket(BucketInfo.builder()
                                .type(BucketType.DIRECTORY)
                                .build())
                        .build())
                .build());
    }

    // ==================== S3 Intelligent Tiering ====================

    /**
     * Enables S3 Intelligent Tiering on the bucket with automatic tiering transitions.
     * <p>
     * S3 Intelligent Tiering automatically moves objects between access tiers based on
     * changing access patterns. This can reduce storage costs by ~40% for data with
     * unknown or changing access patterns.
     * <p>
     * Tiers:
     * - Frequent Access: Objects accessed at least once per month
     * - Infrequent Access: Objects not accessed for 30+ days
     * - Archive Instant Access: Objects not accessed for 90+ days (optional)
     * - Archive Access: Objects not accessed for 180+ days (optional)
     * - Deep Archive Access: Objects not accessed for 365+ days (optional)
     *
     * @param daysToIntelligentTiering Days after which objects move to Intelligent Tiering (default 30)
     */
    public void enableIntelligentTiering(int daysToIntelligentTiering) {
        String bucket = awsProperties.getS3Bucket();

        // Lifecycle rule to transition objects to Intelligent Tiering
        LifecycleRule rule = LifecycleRule.builder()
                .id("bhukkad-intelligent-tiering")
                .status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder()
                        .prefix("")
                        .build())
                .transitions(Transition.builder()
                        .days(daysToIntelligentTiering)
                        .storageClass(TransitionStorageClass.INTELLIGENT_TIERING)
                        .build())
                .build();

        s3Client.putBucketLifecycleConfiguration(PutBucketLifecycleConfigurationRequest.builder()
                .bucket(bucket)
                .lifecycleConfiguration(BucketLifecycleConfiguration.builder()
                        .rules(rule)
                        .build())
                .build());
    }

    /**
     * Enables S3 Intelligent Tiering with Glacier IR tier for cold data.
     * <p>
     * Objects move to Intelligent Tiering after 30 days, then to Glacier IR
     * tier after 180 days for maximum cost savings on cold data.
     */
    public void enableIntelligentTieringWithArchive() {
        String bucket = awsProperties.getS3Bucket();

        LifecycleRule rule = LifecycleRule.builder()
                .id("bhukkad-intelligent-tiering-archive")
                .status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder()
                        .prefix("")
                        .build())
                .transitions(
                        Transition.builder()
                                .days(30)
                                .storageClass(TransitionStorageClass.INTELLIGENT_TIERING)
                                .build(),
                        Transition.builder()
                                .days(180)
                                .storageClass(TransitionStorageClass.GLACIER_IR)
                                .build()
                )
                .build();

        s3Client.putBucketLifecycleConfiguration(PutBucketLifecycleConfigurationRequest.builder()
                .bucket(bucket)
                .lifecycleConfiguration(BucketLifecycleConfiguration.builder()
                        .rules(rule)
                        .build())
                .build());
    }

    /**
     * Disables Intelligent Tiering lifecycle policy on the bucket.
     */
    public void disableIntelligentTiering() {
        s3Client.deleteBucketLifecycle(DeleteBucketLifecycleRequest.builder()
                .bucket(awsProperties.getS3Bucket())
                .build());
    }

    /**
     * Gets the current lifecycle configuration for the bucket.
     */
    public GetBucketLifecycleConfigurationResponse getLifecycleConfiguration() {
        try {
            return s3Client.getBucketLifecycleConfiguration(GetBucketLifecycleConfigurationRequest.builder()
                    .bucket(awsProperties.getS3Bucket())
                    .build());
        } catch (S3Exception e) {
            return null;
        }
    }

    // ==================== Result Records ====================

    public record CompletedUpload(String key, String eTag, Instant completedAt) {}
    public record CompletedDownload(String key, long sizeBytes, Instant completedAt) {}
}