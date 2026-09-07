package com.bhukkad.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    Optional<Tenant> findByDomain(String domain);

    java.util.List<Tenant> findAllByOrderByIdDesc();
}