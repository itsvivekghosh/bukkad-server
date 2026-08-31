package com.bhukkad.repository;

import com.bhukkad.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Registry-level access to identity rows. Since V62 the users table holds
 * only identity + account state (id, role, active, verification flags,
 * audit) — credentials and profile PII live on the per-role tables and are
 * resolved through {@link com.bhukkad.security.AccountLookupService}.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Page<User> findByRole(User.UserRole role, Pageable pageable);

    long countByRole(User.UserRole role);

    long countByActiveTrue();
}
