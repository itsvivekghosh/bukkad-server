package com.bhukkad.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, Long> {

    @Query("SELECT e FROM DeadLetterEvent e WHERE e.status = :status ORDER BY e.createdAt ASC")
    List<DeadLetterEvent> findByStatus(DeadLetterEvent.DlqStatus status, Pageable pageable);

    @Modifying
    @Query("DELETE FROM DeadLetterEvent e WHERE e.status = :status AND e.createdAt < :cutoff")
    int deleteByStatusAndCreatedAtBefore(DeadLetterEvent.DlqStatus status, LocalDateTime cutoff);

    long countByStatus(DeadLetterEvent.DlqStatus status);

    @Modifying
    @Query("UPDATE DeadLetterEvent e SET e.status = :status, e.requeuedAt = :now WHERE e.id = :id")
    int markRequeued(Long id, DeadLetterEvent.DlqStatus status, LocalDateTime now);
}
