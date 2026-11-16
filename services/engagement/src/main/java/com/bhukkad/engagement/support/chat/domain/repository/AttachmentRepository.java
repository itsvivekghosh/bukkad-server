package com.bhukkad.engagement.support.chat.domain.repository;

import com.bhukkad.engagement.support.chat.domain.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Attachment entities.
 */
@Repository
public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    Optional<Attachment> findByPublicId(UUID publicId);

    List<Attachment> findByMessageId(Long messageId);

    List<Attachment> findByRoomId(Long roomId);

    List<Attachment> findByUploadedBy(Long uploadedBy);

    List<Attachment> findByFileHash(String fileHash);

    Optional<Attachment> findByFileHashAndRoomId(String fileHash, Long roomId);

    @Query("SELECT a FROM SupportChatAttachment a WHERE a.messageId = :messageId AND a.mimeType LIKE 'image/%'")
    List<Attachment> findImagesByMessageId(@Param("messageId") Long messageId);

    @Query("SELECT a FROM SupportChatAttachment a WHERE a.isTampered = true ORDER BY a.uploadedAt DESC")
    List<Attachment> findTamperedImages();
}
