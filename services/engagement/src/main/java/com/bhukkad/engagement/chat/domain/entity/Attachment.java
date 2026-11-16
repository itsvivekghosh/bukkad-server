package com.bhukkad.engagement.chat.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Attachment entity for multimedia files (photos, receipts) uploaded in chat messages.
 * Includes metadata for fraud detection via image analysis.
 */
@Entity
@Table(name = "attachments",
       indexes = {
           @Index(name = "idx_attachments_message_id", columnList = "message_id"),
           @Index(name = "idx_attachments_image_hash", columnList = "image_hash"),
           @Index(name = "idx_attachments_exif_datetime", columnList = "exif_datetime_original"),
           @Index(name = "idx_attachments_analysis_status", columnList = "analysis_status"),
           @Index(name = "idx_attachments_fraud_score", columnList = "fraud_risk_score DESC"),
           @Index(name = "idx_attachments_storage_key", columnList = "storage_key")
       })
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@Builder
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attachment_id", nullable = false, unique = true)
    private UUID attachmentId;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", length = 50)
    private ContentType contentType;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_provider", nullable = false, length = 50)
    private StorageProvider storageProvider;

    // Image EXIF/Media metadata for fraud detection
    @Column(name = "image_width")
    private Integer imageWidth;

    @Column(name = "image_height")
    private Integer imageHeight;

    @Column(name = "exif_make", length = 100)
    private String exifMake;

    @Column(name = "exif_model", length = 100)
    private String exifModel;

    @Column(name = "exif_datetime_original")
    private LocalDateTime exifDatetimeOriginal;

    @Column(name = "gps_latitude", precision = 10)
    private Double gpsLatitude;

    @Column(name = "gps_longitude", precision = 11)
    private Double gpsLongitude;

    @Column(name = "compression_ratio")
    private Double compressionRatio;

    // Fraud detection metadata
    @Column(name = "is_duplicate_fingerprint", nullable = false)
    private boolean isDuplicateFingerprint;

    @Column(name = "duplicate_of_attachment_id")
    private UUID duplicateOfAttachmentId;

    @Column(name = "image_hash", length = 64)
    private String imageHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", length = 20)
    private AnalysisStatus analysisStatus;

    @Column(name = "fraud_risk_score")
    private Double fraudRiskScore;

    @Column(name = "fraud_details", columnDefinition = "JSONB")
    private String fraudDetails; // JSON string

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    public enum ContentType {
        ORIGINAL, THUMBNAIL, PROCESSED
    }

    public enum StorageProvider {
        S3, GCS, LOCAL
    }

    public enum AnalysisStatus {
        PENDING, ANALYZED, FLAGGED, CLEARED
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (attachmentId == null) {
            attachmentId = UUID.randomUUID();
        }
    }
}