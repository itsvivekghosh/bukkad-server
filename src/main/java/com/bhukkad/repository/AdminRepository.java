package com.bhukkad.repository;

import com.bhukkad.entity.Admin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AdminRepository extends JpaRepository<Admin, Long> {

    Optional<Admin> findByEmail(String email);

    Optional<Admin> findByPhoneNumber(String phoneNumber);

    Optional<Admin> findByEmailOrPhoneNumber(String email, String phoneNumber);

    Boolean existsByEmail(String email);

    Boolean existsByPhoneNumber(String phoneNumber);

    org.springframework.data.domain.Page<Admin> findByFullNameContainingOrEmailContaining(
            String fullName, String email, org.springframework.data.domain.Pageable pageable);
}
