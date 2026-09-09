package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    boolean existsByDomainIgnoreCase(String domain);
}