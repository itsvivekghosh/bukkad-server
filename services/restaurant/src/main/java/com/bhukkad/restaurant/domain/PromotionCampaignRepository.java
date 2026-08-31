package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PromotionCampaignRepository extends JpaRepository<PromotionCampaign, Long> {
    List<PromotionCampaign> findByActiveTrueAndStartsAtBeforeAndEndsAtAfter(LocalDateTime now, LocalDateTime now2);
}