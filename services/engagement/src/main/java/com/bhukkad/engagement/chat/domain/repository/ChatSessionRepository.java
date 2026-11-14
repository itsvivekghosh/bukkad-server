package com.bhukkad.engagement.chat.domain.repository;

import com.bhukkad.engagement.chat.domain.entity.ChatSession;
import com.bhukkad.engagement.chat.domain.entity.ChatSession.Department;
import com.bhukkad.engagement.chat.domain.entity.ChatSession.Priority;
import com.bhukkad.engagement.chat.domain.entity.ChatSession.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for ChatSession entities.
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    Optional<ChatSession> findBySessionId(UUID sessionId);

    List<ChatSession> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<ChatSession> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    List<ChatSession> findByDepartmentAndStatusOrderByCreatedAtDesc(Department department, Status status);

    List<ChatSession> findByStatusAndPriorityOrderByCreatedAtDesc(Status status, Priority priority);

    @Query("SELECT s FROM ChatSession s WHERE s.customerId = :customerId AND s.status IN :statuses ORDER BY s.createdAt DESC")
    List<ChatSession> findByCustomerIdAndStatusIn(@Param("customerId") Long customerId, 
                                                  @Param("statuses") Status... statuses);

    @Query("SELECT COUNT(s) FROM ChatSession s WHERE s.createdAt >= :since AND s.status = :status")
    long countByCreatedAtAfterAndStatus(@Param("since") LocalDateTime since, @Param("status") Status status);

    @Query("SELECT s FROM ChatSession s WHERE s.createdAt >= :since AND s.updatedAt <= :now")
    List<ChatSession> findStaleOpenSessions(@Param("since") LocalDateTime since, @Param("now") LocalDateTime now);

    long countByCustomerIdAndStatus(Long customerId, Status status);

    long countByDepartmentAndStatus(Department department, Status status);

    long countByStatus(Status status);
}