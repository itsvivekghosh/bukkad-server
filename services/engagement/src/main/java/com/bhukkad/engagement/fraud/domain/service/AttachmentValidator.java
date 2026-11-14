package com.bhukkad.engagement.fraud.domain.service;

import com.bhukkad.engagement.chat.domain.entity.Attachment;
import com.bhukkad.engagement.fraud.config.FraudProperties;
import com.bhukkad.engagement.chat.domain.repository.AttachmentRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates multimedia attachments for fraud detection.
 * Separated from FraudDetectionEngine for Single Responsibility Principle.
 * 
 * <p>Optimizations:
 * <ul>
 *   <li>Caffeine bounded cache with TTL eviction (prevents memory leaks)</li>
 *   <li>Set-based MIME type lookups (O(1) vs O(n))</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class AttachmentValidator {

    private final FraudProperties fraudProperties;
    private final AttachmentRepository attachmentRepository;

    // Caffeine bounded cache for attachment fingerprint tracking
    private final Cache<String, LocalDateTime> attachmentFingerprintCache = Caffeine.newBuilder()
        .maximumSize(500_000)
        .expireAfterWrite(java.time.Duration.ofHours(24))
        .build();

    /**
     * Validates attachment content and metadata.
     */
    public FraudDetectionEngine.AttachmentValidationResult validateAttachment(Attachment attachment) {
        List<String> validationIssues = new ArrayList<>(3);
        boolean isDuplicate = false;
        String duplicateReason = null;

        FraudProperties.Attachments attachmentsConfig = fraudProperties.getAttachments();

        // Check file size
        long maxSize = attachmentsConfig.getMaxFileSizeBytes();
        if (attachment.getFileSizeBytes() > maxSize) {
            validationIssues.add("File size exceeds maximum allowed: " + maxSize + " bytes");
        }

        // Check MIME type using Set for O(1) lookup
        Set<String> allowedMimes = Set.of(attachmentsConfig.getAllowedMimeTypes());
        if (!allowedMimes.contains(attachment.getMimeType())) {
            validationIssues.add("MIME type not allowed: " + attachment.getMimeType());
        }

        // Check for duplicate fingerprints
        String imageHash = attachment.getImageHash();
        if (imageHash != null) {
            List<Attachment> duplicates = attachmentRepository.findByImageHashAnalyzed(imageHash);
            if (!duplicates.isEmpty()) {
                isDuplicate = true;
                duplicateReason = "Duplicate image fingerprint detected: " + duplicates.get(0).getAttachmentId();
            }
        }

        // Image dimension check
        Integer width = attachment.getImageWidth();
        Integer height = attachment.getImageHeight();
        if (width != null && height != null) {
            int maxDim = attachmentsConfig.getMaxImageDimension();
            if (width > maxDim || height > maxDim) {
                validationIssues.add("Image dimensions exceed maximum allowed: " + maxDim);
            }
        }

        // EXIF validation for potential manipulation
        if (attachmentsConfig.isAnalyzeExif()) {
            validateExifIntegrity(attachment, validationIssues);
        }

        // Check cooldown for same attachment
        if (isDuplicate && imageHash != null) {
            LocalDateTime cached = attachmentFingerprintCache.getIfPresent(imageHash);
            if (cached != null) {
                long minutesSince = ChronoUnit.MINUTES.between(cached, LocalDateTime.now());
                long cooldownMinutes = fraudProperties.getDetection().getSameAttachmentCooldownMinutes();
                if (minutesSince < cooldownMinutes) {
                    validationIssues.add("Same attachment uploaded recently: " + minutesSince + " minutes ago");
                }
            }
        }

        return new FraudDetectionEngine.AttachmentValidationResult(
                isDuplicate,
                duplicateReason,
                validationIssues.isEmpty(),
                validationIssues
        );
    }

    private void validateExifIntegrity(Attachment attachment, List<String> issues) {
        LocalDateTime exifDate = attachment.getExifDatetimeOriginal();
        LocalDateTime createdAt = attachment.getCreatedAt();
        
        if (exifDate != null && createdAt != null && exifDate.isAfter(createdAt)) {
            issues.add("EXIF timestamp after creation time");
        }

        if (attachment.getCompressionRatio() != null && attachment.getCompressionRatio() > 99.0) {
            issues.add("Suspicious compression ratio - possible re-compression");
        }
    }

    /**
     * Evicts old cache entries to prevent memory buildup.
     * Can be called periodically via @Scheduled.
     */
    public void evictOldEntries() {
        attachmentFingerprintCache.invalidateAll();
    }
}