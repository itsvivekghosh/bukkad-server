package com.bhukkad.commerce.delivery.domain.repository;
import com.bhukkad.commerce.delivery.domain.entity.DeliveryZone;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryZoneRepository extends JpaRepository<DeliveryZone, Long> {
}