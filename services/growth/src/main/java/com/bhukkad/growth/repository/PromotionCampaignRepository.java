package com.bhukkad.growth.repository;

import com.bhukkad.growth.entity.PromotionCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PromotionCampaignRepository extends JpaRepository<PromotionCampaign, Long> {
}
