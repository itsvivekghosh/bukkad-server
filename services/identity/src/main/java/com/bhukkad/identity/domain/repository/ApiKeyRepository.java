package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.ApiKey;

import com.bhukkad.identity.domain.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {
    Optional<ApiKey> findByKeyHash(String keyHash);

    Optional<ApiKey> findByKeyPrefix(String keyPrefix);
}
