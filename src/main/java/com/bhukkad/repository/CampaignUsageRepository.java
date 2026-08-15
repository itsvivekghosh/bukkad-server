package com.bhukkad.repository;

import com.bhukkad.entity.CampaignUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CampaignUsageRepository extends JpaRepository<CampaignUsage, Long> {
    long countByCampaignId(Long campaignId);

    long countByCampaignIdAndCustomerId(Long campaignId, Long customerId);
}
