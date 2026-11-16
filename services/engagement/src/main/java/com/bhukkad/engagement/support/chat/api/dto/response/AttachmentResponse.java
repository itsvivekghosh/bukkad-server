package com.bhukkad.engagement.support.chat.api.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for attachments.
 */
public record AttachmentResponse(
    UUID publicId,
    Long messageId,
    Long roomId,
    Long uploadedBy,
    String originalFilename,
    String storedFilename,
    String mimeType,
    Long fileSizeBytes,
    String storageUrl,
    String storageProvider,
    String fileHash,
    String exifMetadata,
    Boolean exifAvailable,
    Boolean isTampered,
    LocalDateTime uploadedAt
) {}