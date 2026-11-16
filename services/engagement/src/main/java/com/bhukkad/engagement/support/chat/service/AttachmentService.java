package com.bhukkad.engagement.support.chat.service;

import com.bhukkad.engagement.support.chat.domain.entity.Attachment;
import com.bhukkad.engagement.support.chat.domain.repository.AttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Service for handling file attachments in chat.
 *
 * <p>Stores uploaded files on local filesystem (development) or S3 (production).
 * In production, this would integrate with AWS S3 or similar object storage.</p>
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentRepository attachmentRepository;

    // Storage configuration
    private static final String STORAGE_PATH = System.getProperty("user.home") + "/bhukkad/chat-attachments";
    private static final String STORAGE_PROVIDER = "local"; // Change to "s3" in production

    public AttachmentService(AttachmentRepository attachmentRepository) {
        this.attachmentRepository = attachmentRepository;
        // Ensure storage directory exists
        try {
            Files.createDirectories(Paths.get(STORAGE_PATH));
        } catch (IOException e) {
            log.warn("Could not create attachment storage directory: {}", STORAGE_PATH, e);
        }
    }

    /**
     * Store an uploaded attachment file.
     */
    @Transactional
    public Attachment storeAttachment(Long roomId, Long messageId, Long uploadedBy, MultipartFile file) {
        try {
            String originalFilename = file.getOriginalFilename();
            String storedFilename = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
            Path storagePath = Paths.get(STORAGE_PATH, storedFilename);

            // Write file to disk
            Files.copy(file.getInputStream(), storagePath);

            // Calculate file hash (simplified - in production use SHA-256)
            String fileHash = calculateFileHash(file.getInputStream());

            Attachment attachment = Attachment.builder()
                .messageId(messageId)
                .roomId(roomId)
                .uploadedBy(uploadedBy)
                .originalFilename(originalFilename)
                .storedFilename(storedFilename)
                .mimeType(file.getContentType())
                .fileSizeBytes(file.getSize())
                .storageUrl("/api/v1/support/chat/attachments/" + UUID.randomUUID() + "/download")
                .storageProvider(STORAGE_PROVIDER)
                .fileHash(fileHash)
                .build();

            attachment = attachmentRepository.save(attachment);
            log.info("Attachment stored: {} ({} bytes)", originalFilename, file.getSize());
            return attachment;

        } catch (IOException e) {
            log.error("Failed to store attachment: {}", file.getOriginalFilename(), e);
            throw new RuntimeException("Failed to store attachment: " + e.getMessage(), e);
        }
    }

    /**
     * Get attachment content by public ID.
     */
    @Transactional(readOnly = true)
    public byte[] getAttachment(UUID publicId) {
        Attachment attachment = attachmentRepository.findByPublicId(publicId)
            .orElseThrow(() -> new IllegalArgumentException("Attachment not found: " + publicId));

        Path storagePath = Paths.get(STORAGE_PATH, attachment.getStoredFilename());
        try {
            return Files.readAllBytes(storagePath);
        } catch (IOException e) {
            log.error("Failed to read attachment: {}", attachment.getStoredFilename(), e);
            throw new RuntimeException("Failed to read attachment: " + e.getMessage(), e);
        }
    }

    /**
     * Get attachment MIME type.
     */
    @Transactional(readOnly = true)
    public String getAttachmentMimeType(UUID publicId) {
        Attachment attachment = attachmentRepository.findByPublicId(publicId)
            .orElseThrow(() -> new IllegalArgumentException("Attachment not found: " + publicId));
        return attachment.getMimeType();
    }

    /**
     * Calculate SHA-256 hash of file contents.
     */
    private String calculateFileHash(java.io.InputStream inputStream) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to calculate file hash", e);
            return "unknown";
        }
    }
}
