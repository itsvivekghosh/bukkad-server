package com.bhukkad.admin.analytics.domain.repository;
import com.bhukkad.admin.analytics.domain.entity.ApiKey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findByKeyHashAndStatus(String keyHash, String status);
}
