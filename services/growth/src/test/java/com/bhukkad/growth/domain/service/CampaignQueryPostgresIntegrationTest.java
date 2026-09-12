package com.bhukkad.growth.domain.service;

import com.bhukkad.growth.AbstractGrowthPostgresTest;
import com.bhukkad.growth.api.dto.response.CampaignResponse;
import com.bhukkad.growth.domain.entity.PromotionCampaign;
import com.bhukkad.growth.domain.repository.PromotionCampaignRepository;
import com.bhukkad.growth.domain.service.CampaignService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 structural: the campaign service now answers from the DB. Against real
 * PostgreSQL this pins what the mocked unit tests cannot — the SQL-side
 * schedule-window filter and the priority ordering that replaces the old
 * (never-written) Redis zset ordering.
 */
@SpringBootTest
class CampaignQueryPostgresIntegrationTest extends AbstractGrowthPostgresTest {

    @Autowired
    private CampaignService campaignService;
    @Autowired
    private PromotionCampaignRepository campaignRepository;

    @BeforeEach
    void seed() {
        campaignRepository.deleteAll();
        campaignRepository.saveAndFlush(campaign("never-active", false, null, null, 1));
        campaignRepository.saveAndFlush(campaign("starts-tomorrow", true,
                LocalDateTime.now().plusDays(1), null, 1));
        campaignRepository.saveAndFlush(campaign("ended-yesterday", true,
                null, LocalDateTime.now().minusDays(1), 1));
        campaignRepository.saveAndFlush(campaign("low-priority", true,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), 1));
        campaignRepository.saveAndFlush(campaign("high-priority", true,
                null, null, 9));
    }

    private PromotionCampaign campaign(String name, boolean active,
                                       LocalDateTime starts, LocalDateTime ends, int priority) {
        PromotionCampaign c = new PromotionCampaign();
        c.setName(name);
        c.setActive(active);
        c.setStartsAt(starts);
        c.setEndsAt(ends);
        c.setPriority(priority);
        c.setDiscountPercent(new BigDecimal("10.00"));
        return c;
    }

    @Test
    void activeWindowAndPriorityOnlySurfaceLiveCampaigns() {
        List<CampaignResponse> live = campaignService.getActiveCampaigns();

        assertThat(live).extracting(CampaignResponse::getName)
                .containsExactly("high-priority", "low-priority"); // priority DESC
    }

    @Test
    void getCampaignById_respectsWindow() {
        PromotionCampaign live = campaignRepository.findActiveCampaigns(LocalDateTime.now()).stream()
                .findFirst().orElseThrow();

        assertThat(campaignService.getCampaignById(live.getId())).isNotNull();
        assertThat(campaignService.getCampaignById(
                campaignRepository.findAll().stream()
                        .filter(c -> "never-active".equals(c.getName()))
                        .map(PromotionCampaign::getId).findFirst().orElseThrow())).isNull();
    }
}
