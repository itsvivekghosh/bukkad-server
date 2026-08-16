package com.bhukkad.repository;

import com.bhukkad.entity.CustomerNotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerNotificationPreferenceRepository extends JpaRepository<CustomerNotificationPreference, Long> {
}
