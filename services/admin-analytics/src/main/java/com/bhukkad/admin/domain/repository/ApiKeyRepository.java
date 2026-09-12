package com.bhukkad.admin.domain.repository;
import com.bhukkad.admin.domain.entity.ApiKey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findByKeyHashAndStatus(String keyHash, String status);
}
