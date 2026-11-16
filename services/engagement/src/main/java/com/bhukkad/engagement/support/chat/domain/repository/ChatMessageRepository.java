package com.bhukkad.engagement.support.chat.domain.repository;

import com.bhukkad.engagement.support.chat.domain.entity.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for ChatMessage entities.
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    Optional<ChatMessage> findByPublicId(UUID publicId);

    List<ChatMessage> findByRoomId(Long roomId);

    Page<ChatMessage> findByRoomId(Long roomId, Pageable pageable);

    List<ChatMessage> findByRoomIdOrderByCreatedAtAsc(Long roomId);

    List<ChatMessage> findByRoomIdAndSenderId(Long roomId, Long senderId);

    List<ChatMessage> findByRoomIdAndMessageType(Long roomId, String messageType);

    List<ChatMessage> findByDuplicateHash(String duplicateHash);

    List<ChatMessage> findByFraudScoreGreaterThanEqual(Integer minScore);

    List<ChatMessage> findByIsAutoFlaggedTrue();

    @Query("SELECT m FROM SupportChatMessage m WHERE m.roomId = :roomId AND m.createdAt >= :since ORDER BY m.createdAt ASC")
    List<ChatMessage> findByRoomSince(@Param("roomId") Long roomId, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(m) FROM SupportChatMessage m WHERE m.roomId = :roomId AND m.senderId = :senderId AND m.createdAt >= :since")
    Long countByRoomAndSenderSince(@Param("roomId") Long roomId, @Param("senderId") Long senderId, @Param("since") LocalDateTime since);

    @Query("SELECT m FROM SupportChatMessage m WHERE m.roomId = :roomId AND m.isRead = false AND m.senderId != :senderId ORDER BY m.createdAt ASC")
    List<ChatMessage> findUnreadByRoomAndNotSender(@Param("roomId") Long roomId, @Param("senderId") Long senderId);

    @Query("UPDATE SupportChatMessage m SET m.isRead = true WHERE m.roomId = :roomId AND m.senderId != :senderId AND m.isRead = false")
    int markAsRead(@Param("roomId") Long roomId, @Param("senderId") Long senderId);
}
