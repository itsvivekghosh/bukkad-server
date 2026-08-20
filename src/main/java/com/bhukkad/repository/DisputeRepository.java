package com.bhukkad.repository;

import com.bhukkad.entity.Dispute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    Optional<Dispute> findByOrderId(Long orderId);

    boolean existsByOrderId(Long orderId);

    List<Dispute> findByOrderCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Dispute> findByStatusOrderByCreatedAtDesc(Dispute.DisputeStatus status);

    List<Dispute> findByStatusInOrderByCreatedAtAsc(List<Dispute.DisputeStatus> statuses);
}
