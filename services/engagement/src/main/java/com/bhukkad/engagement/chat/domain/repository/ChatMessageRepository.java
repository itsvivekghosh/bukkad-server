package com.bhukkad.engagement.chat.domain.repository;

import com.bhukkad.engagement.chat.domain.entity.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for ChatMessage entities.
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    Optional<ChatMessage> findByMessageId(UUID messageId);

    @Query("SELECT m FROM ChatMessage m WHERE m.sessionId = :sessionId AND m.createdAt >= :since ORDER BY m.createdAt ASC")
    List<ChatMessage> findRecentBySessionId(@Param("sessionId") UUID sessionId, @Param("since") LocalDateTime since);

    @Query("SELECT m FROM ChatMessage m WHERE m.sessionId = :sessionId AND m.isFraudFlagged = true ORDER BY m.createdAt DESC")
    List<ChatMessage> findFlaggedBySessionId(@Param("sessionId") UUID sessionId);

    Page<ChatMessage> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);

    List<ChatMessage> findBySenderIdAndCreatedAtAfter(Long senderId, LocalDateTime since);

    @Query("SELECT m FROM ChatMessage m WHERE m.sessionId = :sessionId AND m.replyToMessageId = :replyToId ORDER BY m.createdAt ASC")
    List<ChatMessage> findBySessionIdAndReplyToMessageId(@Param("sessionId") UUID sessionId, 
                                                          @Param("replyToId") UUID replyToId);

    long countBySessionId(UUID sessionId);

    long countBySessionIdAndIsFraudFlagged(UUID sessionId, boolean isFraudFlagged);

    long countBySenderIdAndCreatedAtAfter(Long senderId, LocalDateTime since);

    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.createdAt >= :since AND m.isFraudFlagged = true")
    long countFraudulentMessagesSince(@Param("since") LocalDateTime since);
}