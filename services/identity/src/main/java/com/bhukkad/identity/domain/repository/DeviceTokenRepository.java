package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.DeviceToken;

import com.bhukkad.identity.domain.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
    List<DeviceToken> findByUserId(Long userId);

    Optional<DeviceToken> findByUserIdAndToken(Long userId, String token);

    Optional<DeviceToken> findByToken(String token);

    long deleteByUserIdAndToken(Long userId, String token);
}