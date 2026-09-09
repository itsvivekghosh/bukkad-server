package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Restaurant-service port of the monolith
 * {@code com.bhukkad.repository.CampaignUsageRepository} (plain-ID columns
 * keep the derived queries identical).
 */
@Repository
public interface CampaignUsageRepository extends JpaRepository<CampaignUsage, Long> {

    long countByCampaignId(Long campaignId);

    long countByCampaignIdAndCustomerId(Long campaignId, Long customerId);
}
