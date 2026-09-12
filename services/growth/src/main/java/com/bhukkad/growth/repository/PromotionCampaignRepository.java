package com.bhukkad.growth.repository;

import com.bhukkad.growth.entity.PromotionCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PromotionCampaignRepository extends JpaRepository<PromotionCampaign, Long> {

    /**
     * Currently-running campaigns: active flag set and the wall clock inside
     * the optional start/end window (a null bound means open-ended, matching
     * the nullable schedule columns). Priority descending mirrors the old
     * Redis zset ordering ("sorted by priority descending"); id ties keep the
     * result deterministic.
     */
    @Query("""
            SELECT c FROM PromotionCampaign c
            WHERE c.active = true
              AND (c.startsAt IS NULL OR c.startsAt <= :now)
              AND (c.endsAt IS NULL OR c.endsAt >= :now)
            ORDER BY c.priority DESC, c.id ASC
            """)
    List<PromotionCampaign> findActiveCampaigns(@Param("now") LocalDateTime now);

    /** Single active campaign within its schedule window (404 path otherwise). */
    @Query("""
            SELECT c FROM PromotionCampaign c
            WHERE c.id = :id
              AND c.active = true
              AND (c.startsAt IS NULL OR c.startsAt <= :now)
              AND (c.endsAt IS NULL OR c.endsAt >= :now)
            """)
    Optional<PromotionCampaign> findActiveCampaign(@Param("id") Long id,
                                                   @Param("now") LocalDateTime now);
}
