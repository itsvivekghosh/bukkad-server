package com.bhukkad.repository;

import com.bhukkad.entity.GiftCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GiftCardRepository extends JpaRepository<GiftCard, Long> {

    Optional<GiftCard> findByCode(String code);

    boolean existsByCode(String code);

    List<GiftCard> findByPurchasedById(Long purchasedById);

    List<GiftCard> findByRecipientEmailAndStatus(String recipientEmail, GiftCard.Status status);
}