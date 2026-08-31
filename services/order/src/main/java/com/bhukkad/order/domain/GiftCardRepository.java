package com.bhukkad.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GiftCardRepository extends JpaRepository<GiftCard, Long> {
    Optional<GiftCard> findByCodeAndStatus(String code, String status);
}