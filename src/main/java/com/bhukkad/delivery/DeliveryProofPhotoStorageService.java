package com.bhukkad.delivery;

import com.bhukkad.exception.BusinessException;
import com.bhukkad.storage.ImageStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Issues upload and download URLs for delivery proof-of-delivery photos.
 *
 * <p>Riders never stream image bytes through this API. Consistent with
 * {@code MenuImageService}, the rider app asks for a short-lived presigned
 * {@code PUT} URL, uploads directly to object storage, and then reports the
 * resulting key back to {@link DeliveryProofService}. That keeps large binary
 * payloads off the application servers entirely, which matters on the patchy
 * mobile connections riders actually work on.
 *
 * <p>Object storage is optional in this deployment: the {@code S3Presigner}
 * bean only exists when {@code app.storage.s3.enabled=true}, so it is injected
 * with {@code required = false}. The two directions degrade differently and
 * deliberately so:
 * <ul>
 *   <li>{@link #createUploadUrl(String, String)} throws when storage is
 *       disabled. A rider asking to upload a photo must get a clear error
 *       rather than a silent no-op, otherwise the app would show a successful
 *       capture for a photo that was never stored.</li>
 *   <li>{@link #presignedUrl(String)} returns {@code null} when storage is
 *       disabled or the proof has no photo, so support and ops responses simply
 *       omit the link instead of failing the whole payload.</li>
 * </ul>
 *
 * <p>Photos live under their own key prefix ({@value #KEY_PREFIX}), separate
 * from both menu images and invoice PDFs. This is a hard requirement, not
 * tidiness: {@code MenuImageService.validateImageKey} accepts only keys under
 * the menu-image prefix, and {@link #validateKey(String)} here mirrors that
 * check for proof keys. A distinct prefix means neither service can be
 * persuaded to presign the other's objects.
 *
 * <p>Keys are partitioned by capture date and carry a random UUID, so a rider
 * cannot overwrite an earlier proof (their own or anyone else's) by replaying a
 * key, and bucket listings stay navigable as volume grows.
 *
 * <p>Proof photos can show a customer's doorstep, so they are treated as
 * private: downloads always go through a short-lived presigned URL and objects
 * are never made public.
 */
@Slf4j
@Service
public class DeliveryProofPhotoStorageService {

    /**
     * Root key prefix for all delivery proof photos. Kept distinct from the
     * menu-image and invoice prefixes so each service validates only its own.
     */
    public static final String KEY_PREFIX = "delivery-proofs";

    /**
     * Formats a rider phone camera can realistically produce. Deliberately
     * narrower than a generic image allowlist: no SVG, because SVG is an active
     * document format and these objects are later handed back to internal
     * dashboards via presigned URLs.
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private final ImageStorageProperties properties;
    private final S3Presigner s3Presigner;

    public DeliveryProofPhotoStorageService(
            ImageStorageProperties properties,
            @Autowired(required = false) S3Presigner s3Presigner) {
        this.properties = properties;
        this.s3Presigner = s3Presigner;
    }

    /**
     * Reports whether proof photos can be captured in this deployment.
     *
     * <p>Callers use this to decide whether to offer a photo step at all,
     * rather than letting a rider reach an upload that is guaranteed to fail.
     *
     * @return {@code true} when the bucket is configured and presigning is available
     */
    public boolean isEnabled() {
        return properties.isEnabled() && s3Presigner != null;
    }

    /**
     * Builds the storage key for a proof photo, partitioned by capture date.
     *
     * <p>The UUID component makes the key unguessable and non-reusable, so a
     * replayed upload request cannot clobber an existing proof.
     *
     * @param orderId     order the proof belongs to
     * @param contentType declared image content type, validated here
     * @return deterministic-prefix object key, e.g.
     *         {@code delivery-proofs/2026/08/42/3f1c....jpg}
     * @throws BusinessException if the content type is not an accepted image format
     */
    public String buildKey(Long orderId, String contentType) {
        validateContentType(contentType);
        LocalDate today = LocalDate.now();
        return String.format("%s/%d/%02d/%d/%s%s",
                KEY_PREFIX, today.getYear(), today.getMonthValue(), orderId,
                UUID.randomUUID(), extensionFor(contentType));
    }

    /**
     * Issues a short-lived presigned {@code PUT} URL the rider app uploads to
     * directly.
     *
     * <p>Unlike the download side, this fails loudly: a rider must not be shown
     * a successful photo capture for an upload that could never have been
     * stored.
     *
     * @param photoKey    key previously produced by {@link #buildKey(Long, String)}
     * @param contentType image content type the rider app will send
     * @return presigned HTTPS upload URL, valid for the configured upload expiry
     * @throws BusinessException if storage is disabled, presigning is unavailable,
     *                           the key is not a proof-photo key, or the content
     *                           type is unsupported
     */
    public String createUploadUrl(String photoKey, String contentType) {
        if (!properties.isEnabled()) {
            throw new BusinessException("Delivery proof photo uploads are not enabled");
        }
        validateKey(photoKey);
        validateContentType(contentType);

        S3Presigner presigner = requirePresigner();

        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(photoKey)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.getUploadUrlExpirySeconds()))
                .putObjectRequest(objectRequest)
                .build();

        String url = presigner.presignPutObject(presignRequest).url().toString();
        log.info("DELIVERY_PROOF_PHOTO_UPLOAD_URL_ISSUED | key={} | contentType={} | expirySeconds={}",
                photoKey, contentType, properties.getUploadUrlExpirySeconds());
        return url;
    }

    /**
     * Issues a short-lived download URL for a stored proof photo.
     *
     * <p>Never throws: proof photos are surfaced alongside order and dispute
     * data, and a storage outage must not fail those reads. A missing link is
     * degraded information; a failed response is a broken screen.
     *
     * @param storageKey key recorded on the proof row, may be {@code null}
     * @return presigned HTTPS URL, or {@code null} when there is no photo or
     *         presigning is unavailable
     */
    public String presignedUrl(String storageKey) {
        if (!StringUtils.hasText(storageKey) || !properties.isEnabled() || s3Presigner == null) {
            return null;
        }
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(storageKey)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(properties.getDownloadUrlExpirySeconds()))
                    .getObjectRequest(getRequest)
                    .build();

            return s3Presigner.presignGetObject(presignRequest).url().toString();
        } catch (Exception ex) {
            log.error("DELIVERY_PROOF_PHOTO_PRESIGN_FAILED | key={} | error={}",
                    storageKey, ex.getMessage(), ex);
            return null;
        }
    }

    /**
     * Rejects any key that is not a delivery proof photo key.
     *
     * <p>Riders send the key back to the server when confirming a proof, so it
     * is untrusted input. Without this check a rider could hand over an invoice
     * or menu-image key and have this service presign it.
     *
     * @param photoKey candidate key from the client
     * @throws BusinessException if the key is blank or outside {@value #KEY_PREFIX}
     */
    public void validateKey(String photoKey) {
        if (!StringUtils.hasText(photoKey)) {
            throw new BusinessException("Delivery proof photo key is required");
        }
        if (!photoKey.startsWith(KEY_PREFIX + "/")) {
            throw new BusinessException("Invalid delivery proof photo key");
        }
    }

    /**
     * Validates the declared image content type against the allowlist.
     *
     * @param contentType content type from the client
     * @throws BusinessException if the type is blank or not an accepted image format
     */
    public void validateContentType(String contentType) {
        if (!StringUtils.hasText(contentType)
                || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("Unsupported delivery proof photo content type");
        }
    }

    private String extensionFor(String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
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
