package com.bhukkad.delivery.domain.repository;
import com.bhukkad.delivery.domain.entity.DeliveryZone;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryZoneRepository extends JpaRepository<DeliveryZone, Long> {
}