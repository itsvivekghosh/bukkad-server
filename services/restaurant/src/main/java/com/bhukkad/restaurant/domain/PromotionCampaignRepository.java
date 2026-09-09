package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PromotionCampaignRepository extends JpaRepository<PromotionCampaign, Long> {
    List<PromotionCampaign> findByActiveTrueAndStartsAtBeforeAndEndsAtAfter(LocalDateTime now, LocalDateTime now2);

    @Query("""
            SELECT c FROM PromotionCampaign c
            WHERE c.active = true
              AND (c.startsAt IS NULL OR c.startsAt <= :now)
              AND (c.endsAt IS NULL OR c.endsAt >= :now)
            ORDER BY c.discountPercent DESC NULLS LAST
            """)
    List<PromotionCampaign> findActiveCampaigns(@Param("now") LocalDateTime now);
}