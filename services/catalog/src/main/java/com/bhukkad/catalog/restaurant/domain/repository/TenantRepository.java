package com.bhukkad.catalog.restaurant.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.bhukkad.catalog.restaurant.domain.entity.Tenant;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    boolean existsByDomainIgnoreCase(String domain);
}