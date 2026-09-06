package com.bhukkad.admin.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, Long> {
    List<DeadLetterEvent> findAllByOrderByCreatedAtDesc(org.springframework.data.domain.Pageable pageable);

    List<DeadLetterEvent> findByStatus(String status);
}
