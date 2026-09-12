package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.CustomerNotificationPreference;

import com.bhukkad.identity.domain.entity.CustomerNotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerNotificationPreferenceRepository extends JpaRepository<CustomerNotificationPreference, Long> {
    Optional<CustomerNotificationPreference> findByCustomerIdAndChannel(Long customerId, String channel);
}