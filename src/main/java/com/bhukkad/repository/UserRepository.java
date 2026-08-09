package com.bhukkad.repository;

import com.bhukkad.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Boolean existsByEmail(String email);
    Boolean existsByPhoneNumber(String phoneNumber);
    Page<User> findByRole(User.UserRole role, Pageable pageable);
    Page<User> findByFullNameContainingOrEmailContaining(String name, String email, Pageable pageable);
    long countByRole(User.UserRole role);
}