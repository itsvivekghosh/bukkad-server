package com.bhukkad.engagement.support.chat.domain.repository;

import com.bhukkad.engagement.support.chat.domain.entity.OrderReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for OrderReference entities.
 */
@Repository
public interface OrderReferenceRepository extends JpaRepository<OrderReference, Long> {

    Optional<OrderReference> findByPublicId(UUID publicId);

    List<OrderReference> findByRoomId(Long roomId);

    List<OrderReference> findByOrderId(Long orderId);

    List<OrderReference> findByCustomerId(Long customerId);

    boolean existsByRoomIdAndOrderId(Long roomId, Long orderId);
}
