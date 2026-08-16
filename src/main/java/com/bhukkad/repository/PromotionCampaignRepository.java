package com.bhukkad.repository;

import com.bhukkad.entity.PromotionCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PromotionCampaignRepository extends JpaRepository<PromotionCampaign, Long> {

    @Query("""
            SELECT c FROM PromotionCampaign c
            WHERE c.isActive = true
              AND (c.startsAt IS NULL OR c.startsAt <= :now)
              AND (c.endsAt IS NULL OR c.endsAt >= :now)
            ORDER BY c.discountPercent DESC NULLS LAST
            """)
    List<PromotionCampaign> findActiveCampaigns(LocalDateTime now);
}
