package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.Tenant;

import com.bhukkad.identity.domain.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    Optional<Tenant> findByDomain(String domain);

    java.util.List<Tenant> findAllByOrderByIdDesc();
}