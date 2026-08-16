package com.bhukkad.invoice;

import com.bhukkad.storage.ImageStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Persists rendered GST invoice PDFs to object storage and hands out
 * time-limited download URLs.
 *
 * <p>Object storage is optional in this deployment: the {@code S3Client} and
 * {@code S3Presigner} beans are declared
 * {@code @ConditionalOnProperty(app.storage.s3.enabled=true)}, so in local and
 * test profiles neither bean exists. Both are therefore injected with
 * {@code required = false} and every method degrades gracefully:
 * <ul>
 *   <li>{@link #store(String, byte[])} returns {@code null} when storage is
 *       disabled, which the caller records as "no stored copy". The invoice row
 *       is still written and the PDF is still re-renderable on demand from the
 *       persisted amounts, so a disabled bucket never blocks invoicing.</li>
 *   <li>{@link #presignedUrl(String)} returns {@code null} rather than throwing,
 *       so API responses simply omit the link.</li>
 * </ul>
 *
 * <p>Invoice objects live under their own key prefix ({@value #KEY_PREFIX}),
 * deliberately separate from the menu-image prefix owned by
 * {@code MenuImageService}, because that service validates every key against
 * its own prefix and must not accept invoice keys. Keys are partitioned by
 * issue date to keep bucket listings manageable as volume grows.
 *
 * <p>Financial documents are never made public: downloads always go through a
 * short-lived presigned URL derived from the configured download expiry.
 */
@Slf4j
@Service
public class InvoicePdfStorageService {

    /** Root key prefix for all invoice PDFs. Kept distinct from menu images. */
    public static final String KEY_PREFIX = "invoices";

    private static final String CONTENT_TYPE = "application/pdf";

    private final ImageStorageProperties properties;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    public InvoicePdfStorageService(
            ImageStorageProperties properties,
            @Autowired(required = false) S3Client s3Client,
            @Autowired(required = false) S3Presigner s3Presigner) {
        this.properties = properties;
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    /**
     * Reports whether stored copies are possible in this deployment.
     *
     * @return {@code true} when the bucket is configured and the client bean exists
     */
    public boolean isEnabled() {
        return properties.isEnabled() && s3Client != null;
    }

    /**
     * Builds the storage key for an invoice, partitioned by issue date.
     *
     * @param invoiceNumber unique invoice number, used as the object name
     * @return deterministic object key, e.g. {@code invoices/2026/08/INV-2026-00000042.pdf}
     */
    public String buildKey(String invoiceNumber) {
        LocalDate today = LocalDate.now();
        return String.format("%s/%d/%02d/%s.pdf",
                KEY_PREFIX, today.getYear(), today.getMonthValue(), invoiceNumber);
    }

    /**
     * Uploads invoice PDF bytes.
     *
     * <p>Never throws: a storage outage must not roll back an invoice or block
     * an order from being marked delivered. Failures are logged and reported as
     * a {@code null} key so the caller can retry later or fall back to
     * on-demand rendering.
     *
     * @param invoiceNumber invoice number used to derive the key
     * @param pdf           rendered PDF bytes
     * @return the stored object key, or {@code null} if storage is disabled or the upload failed
     */
    public String store(String invoiceNumber, byte[] pdf) {
        if (!isEnabled()) {
            log.debug("INVOICE_PDF_STORAGE_DISABLED | invoice={} | serving on demand instead", invoiceNumber);
            return null;
        }
        if (pdf == null || pdf.length == 0) {
            return null;
        }

        String key = buildKey(invoiceNumber);
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .contentType(CONTENT_TYPE)
                    .contentLength((long) pdf.length)
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(pdf));
            log.info("INVOICE_PDF_STORED | invoice={} | key={} | bytes={}", invoiceNumber, key, pdf.length);
            return key;
        } catch (Exception ex) {
            log.error("INVOICE_PDF_STORE_FAILED | invoice={} | key={} | error={}",
                    invoiceNumber, key, ex.getMessage(), ex);
            return null;
        }
    }

    /**
     * Issues a short-lived download URL for a stored invoice PDF.
     *
     * @param storageKey key returned by {@link #store(String, byte[])}
     * @return presigned HTTPS URL, or {@code null} when there is no stored copy
     *         or presigning is unavailable
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
            log.error("INVOICE_PDF_PRESIGN_FAILED | key={} | error={}", storageKey, ex.getMessage(), ex);
            return null;
        }
    }
}
