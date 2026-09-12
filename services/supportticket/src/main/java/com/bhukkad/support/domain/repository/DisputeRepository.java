package com.bhukkad.support.domain.repository;

import com.bhukkad.support.domain.entity.Dispute;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DisputeRepository
extends JpaRepository<Dispute, Long> {
    public Optional<Dispute> findByOrderId(Long var1);

    public boolean existsByOrderId(Long var1);

    public List<Dispute> findByCustomerIdOrderByCreatedAtDesc(Long var1);

    /** PERF-3: newest-first admin history, bounded page + ORDER BY in SQL. */
    public List<Dispute> findAllByOrderByCreatedAtDesc(Pageable var1);

    public List<Dispute> findByStatusOrderByCreatedAtDesc(Dispute.DisputeStatus var1);

    public List<Dispute> findByStatusInOrderByCreatedAtAsc(List<Dispute.DisputeStatus> var1);
}

