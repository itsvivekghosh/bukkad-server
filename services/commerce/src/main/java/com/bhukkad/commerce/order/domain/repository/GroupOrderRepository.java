package com.bhukkad.commerce.order.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.bhukkad.commerce.order.domain.entity.GroupOrder;

public interface GroupOrderRepository extends JpaRepository<GroupOrder, Long> {
}