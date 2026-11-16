package com.bhukkad.engagement.support.chat.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Attachment represents a file uploaded as part of a chat message.
 *
 * <p>Supported file types: images (JPEG, PNG, WEBP), documents (PDF, DOCX),
 * and receipts. Files are stored in object storage (S3/MinIO) and only the
 * metadata + URL are persisted here.</p>
 *
 * <p>Anti-fraud integration: attachment metadata (file size, MIME type,
 * upload timestamp) is recorded for forensic analysis. Images are checked
 * for EXIF data integrity to detect tampering or stock-photo usage.</p>
 */
@Entity(name = "SupportChatAttachment")
@Table(name = "chat_attachments", indexes = {
    @Index(name = "idx_attachment_message", columnList = "message_id"),
    @Index(name = "idx_attachment_uploaded", columnList = "uploaded_at"),
    @Index(name = "idx_attachment_mime", columnList = "mime_type"),
    @Index(name = "idx_attachment_file_hash", columnList = "file_hash")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", unique = true, nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID publicId;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "original_filename", length = 255, nullable = false)
    private String originalFilename;

    @Column(name = "stored_filename", length = 255, nullable = false)
    private String storedFilename;

    @Column(name = "mime_type", length = 100, nullable = false)
    private String mimeType;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Column(name = "storage_url", length = 1000, nullable = false)
    private String storageUrl;

    @Column(name = "storage_provider", length = 50, nullable = false)
    @Builder.Default
    private String storageProvider = "s3";

    /**
     * SHA-256 hash of file contents for duplicate detection.
     */
    @Column(name = "file_hash", length = 64)
    private String fileHash;

    /**
     * EXIF metadata extracted from images (JSON). Used for fraud detection:
     * camera model, GPS coordinates, timestamp, software used.
     */
    @Column(name = "exif_metadata", length = 4000)
    private String exifMetadata;

    /**
     * Whether EXIF data was found and parsed.
     */
    @Column(name = "exif_available", nullable = false)
    @Builder.Default
    private Boolean exifAvailable = false;

    /**
     * Whether the image appears to be tampered (EXIF inconsistencies).
     */
    @Column(name = "is_tampered", nullable = false)
    @Builder.Default
    private Boolean isTampered = false;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    @PrePersist
    protected void onCreate() {
        if (publicId == null) {
            publicId = java.util.UUID.randomUUID();
        }
        if (uploadedAt == null) {
            uploadedAt = java.time.LocalDateTime.now();
        }
        if (exifAvailable == null) {
            exifAvailable = false;
        }
        if (isTampered == null) {
            isTampered = false;
        }
    }
}
