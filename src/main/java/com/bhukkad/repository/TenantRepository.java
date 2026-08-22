package com.bhukkad.repository;

import com.bhukkad.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findByDomainIgnoreCase(String domain);

    boolean existsByDomainIgnoreCase(String domain);

    List<Tenant> findByIsActiveTrueOrderByNameAsc();
}
