package com.bhukkad.growth.repository;

import com.bhukkad.growth.entity.PromotionCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PromotionCampaignRepository extends JpaRepository<PromotionCampaign, Long> {

    /**
     * Campaigns currently serving: active flag on and the optional start/end
     * window containing {@code now}. Priority descending is the serving order
     * (highest priority first).
     */
    @Query("""
            SELECT c FROM PromotionCampaign c
            WHERE c.active = true
              AND (c.startsAt IS NULL OR c.startsAt <= :now)
              AND (c.endsAt IS NULL OR c.endsAt >= :now)
            ORDER BY c.priority DESC
            """)
    List<PromotionCampaign> findCurrentlyActive(@Param("now") LocalDateTime now);
}
