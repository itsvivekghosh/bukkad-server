package com.bhukkad.order.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.bhukkad.order.domain.entity.GroupOrder;

public interface GroupOrderRepository extends JpaRepository<GroupOrder, Long> {
}