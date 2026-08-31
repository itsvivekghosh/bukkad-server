package com.bhukkad.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupOrderRepository extends JpaRepository<GroupOrder, Long> {
}