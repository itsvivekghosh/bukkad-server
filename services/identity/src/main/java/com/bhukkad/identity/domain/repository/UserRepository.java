package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}