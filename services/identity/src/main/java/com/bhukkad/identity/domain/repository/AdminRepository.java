package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.Admin;

import com.bhukkad.identity.domain.entity.Admin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminRepository extends JpaRepository<Admin, Long> {
    Optional<Admin> findByEmail(String email);

    boolean existsByEmail(String email);
}
