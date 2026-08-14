package com.bhukkad.storage;

import com.bhukkad.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class MenuImageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif"
    );

    private final ImageStorageProperties properties;
    private final S3Presigner s3Presigner;

    public MenuImageService(
            ImageStorageProperties properties,
            @Autowired(required = false) S3Presigner s3Presigner) {
        this.properties = properties;
        this.s3Presigner = s3Presigner;
    }

    public String generateImageKey(Long restaurantId, Long menuItemId, String contentType) {
        validateContentType(contentType);
        String extension = extensionFor(contentType);
        return properties.getKeyPrefix() + "/" + restaurantId + "/" + menuItemId + "/"
                + UUID.randomUUID() + extension;
    }

    public String createUploadUrl(String imageKey, String contentType) {
        if (!properties.isEnabled()) {
            throw new BusinessException("Image uploads are not enabled");
        }
        validateImageKey(imageKey);
        validateContentType(contentType);

        S3Presigner presigner = requirePresigner();

        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(imageKey)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.getUploadUrlExpirySeconds()))
                .putObjectRequest(objectRequest)
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);
        return presigned.url().toString();
    }

    public String resolvePublicUrl(String storedValue) {
        if (!StringUtils.hasText(storedValue)) {
            return null;
        }
        if (storedValue.startsWith("http://") || storedValue.startsWith("https://")) {
            return storedValue;
        }
        if (!properties.isEnabled()) {
            return storedValue;
        }

        if (s3Presigner == null) {
            return storedValue;
        }

        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(storedValue)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.getDownloadUrlExpirySeconds()))
                .getObjectRequest(getRequest)
                .build();

        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
        return presigned.url().toString();
    }

    public void validateImageKey(String imageKey) {
        if (!StringUtils.hasText(imageKey)) {
            throw new BusinessException("Image key is required");
        }
        String prefix = properties.getKeyPrefix() + "/";
        if (!imageKey.startsWith(prefix)) {
            throw new BusinessException("Invalid image key");
        }
    }

    public void validateContentType(String contentType) {
        if (!StringUtils.hasText(contentType)
                || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("Unsupported image content type");
        }
    }

    private String extensionFor(String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }

    private S3Presigner requirePresigner() {
        if (s3Presigner == null) {
            throw new BusinessException("S3 presigner is not configured");
        }
        return s3Presigner;
    }
}
