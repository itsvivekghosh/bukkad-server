package com.bhukkad.restaurant.service;

import com.bhukkad.restaurant.domain.PromotionCampaign;
import com.bhukkad.restaurant.domain.PromotionCampaignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionCampaignServiceTest {

    @Mock
    private PromotionCampaignRepository promotionCampaignRepository;

    @InjectMocks
    private PromotionCampaignService service;

    private PromotionCampaign active;

    @BeforeEach
    void setUp() {
        active = new PromotionCampaign();
        active.setId(1L);
        active.setName("Spring Sale");
        active.setDiscountPct(new BigDecimal("10.00"));
        active.setMaxDiscount(new BigDecimal("50.00"));
        active.setStartsAt(LocalDateTime.now().minusDays(1));
        active.setEndsAt(LocalDateTime.now().plusDays(1));
        active.setActive(true);
    }

    @Test
    void listActive_filtersToRunningCampaigns() {
        when(promotionCampaignRepository.findByActiveTrueAndStartsAtBeforeAndEndsAtAfter(any(), any()))
                .thenReturn(List.of(active));

        assertThat(service.listActive()).containsExactly(active);
    }

    @Test
    void getBestDiscount_appliesPercentage() {
        when(promotionCampaignRepository.findByActiveTrueAndStartsAtBeforeAndEndsAtAfter(any(), any()))
                .thenReturn(List.of(active));

        BigDecimal discount = service.getBestDiscount(new BigDecimal("200.00"));

        assertThat(discount).isEqualByComparingTo("20.00");
    }

    @Test
    void getBestDiscount_capsAtMaxDiscount() {
        when(promotionCampaignRepository.findByActiveTrueAndStartsAtBeforeAndEndsAtAfter(any(), any()))
                .thenReturn(List.of(active));

        BigDecimal discount = service.getBestDiscount(new BigDecimal("1000.00"));

        // 10% of 1000 = 100, but capped at 50.
        assertThat(discount).isEqualByComparingTo("50.00");
    }

    @Test
    void getBestDiscount_returnsZero_whenNoActiveCampaigns() {
        when(promotionCampaignRepository.findByActiveTrueAndStartsAtBeforeAndEndsAtAfter(any(), any()))
                .thenReturn(List.of());

        assertThat(service.getBestDiscount(new BigDecimal("100.00"))).isEqualByComparingTo("0");
    }

    @Test
    void getBestDiscount_handlesNullSubtotal() {
        assertThat(service.getBestDiscount(null)).isEqualByComparingTo("0");
    }
}