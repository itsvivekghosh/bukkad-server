package com.bhukkad.engagement.support.chat.domain.repository;

import com.bhukkad.engagement.support.chat.domain.entity.ChatRoom;
import com.bhukkad.engagement.support.chat.domain.entity.ChatRoom.ChatRoomStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for ChatRoom entities.
 */
@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByPublicId(UUID publicId);

    List<ChatRoom> findByCustomerId(Long customerId);

    List<ChatRoom> findByStatus(ChatRoomStatus status);

    List<ChatRoom> findByStatusAndCustomerId(ChatRoomStatus status, Long customerId);

    List<ChatRoom> findByAgentId(String agentId);

    List<ChatRoom> findByOrderId(Long orderId);

    List<ChatRoom> findByRestaurantId(Long restaurantId);

    @Query("SELECT r FROM ChatRoom r WHERE r.fraudFlagged = true ORDER BY r.fraudScore DESC, r.createdAt DESC")
    List<ChatRoom> findFraudFlaggedRooms();

    @Query("SELECT r FROM ChatRoom r WHERE r.fraudScore >= :minScore ORDER BY r.fraudScore DESC")
    List<ChatRoom> findByFraudScoreGreaterThanEqual(@Param("minScore") Integer minScore);

    @Query("SELECT COUNT(r) FROM ChatRoom r WHERE r.status = :status")
    Long countByStatus(ChatRoomStatus status);

    @Query("SELECT r FROM ChatRoom r WHERE r.customerId = :customerId AND r.createdAt >= :since ORDER BY r.createdAt DESC")
    List<ChatRoom> findByCustomerSince(@Param("customerId") Long customerId, @Param("since") LocalDateTime since);

    boolean existsByCustomerIdAndOrderId(Long customerId, Long orderId);
}
