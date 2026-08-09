package com.bhukkad.repository;

import com.bhukkad.entity.DeliveryAgent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryAgentRepository extends JpaRepository<DeliveryAgent, Long> {
    Optional<DeliveryAgent> findByEmail(String email);

    List<DeliveryAgent> findByAvailableTrueAndVerifiedTrue();

    @Query("SELECT d FROM DeliveryAgent d WHERE d.available = true AND d.verified = true " +
            "ORDER BY d.totalDeliveries ASC")
    List<DeliveryAgent> findAvailableAgents();
}