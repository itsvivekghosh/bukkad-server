package com.bhukkad.restaurant.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.bhukkad.restaurant.domain.entity.Tenant;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    boolean existsByDomainIgnoreCase(String domain);
}