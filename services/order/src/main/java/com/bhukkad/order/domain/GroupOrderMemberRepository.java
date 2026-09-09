package com.bhukkad.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GroupOrderMemberRepository extends JpaRepository<GroupOrderMember, Long> {
    List<GroupOrderMember> findByGroupOrderId(Long groupOrderId);
    Optional<GroupOrderMember> findByGroupOrderIdAndCustomerId(Long groupOrderId, Long customerId);
}