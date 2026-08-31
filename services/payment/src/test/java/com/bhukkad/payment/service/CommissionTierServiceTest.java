package com.bhukkad.payment.service;

import com.bhukkad.payment.domain.CommissionTier;
import com.bhukkad.payment.domain.CommissionTierRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommissionTierServiceTest {

    @Mock private CommissionTierRepository tierRepository;
    @InjectMocks private CommissionTierService service;

    private CommissionTier tier(Integer min, Integer max, String pct) {
        CommissionTier t = new CommissionTier();
        t.setMinOrderCount(min);
        t.setMaxOrderCount(max);
        t.setCommissionPct(new BigDecimal(pct));
        return t;
    }

    @Test
    void create_persistsTierWithConfiguredRange() {
        when(tierRepository.save(org.mockito.ArgumentMatchers.any(CommissionTier.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CommissionTier tier = service.create(10, 50, new BigDecimal("8.00"));

        assertThat(tier.getMinOrderCount()).isEqualTo(10);
        assertThat(tier.getMaxOrderCount()).isEqualTo(50);
        assertThat(tier.getCommissionPct()).isEqualByComparingTo("8.00");
        verify(tierRepository).save(tier);
    }

    @Test
    void active_returnsTiersFromRepository() {
        List<CommissionTier> tiers = List.of(tier(0, 10, "5.00"), tier(11, null, "8.00"));
        when(tierRepository.findByActiveTrueOrderByMinOrderCountAsc()).thenReturn(tiers);

        assertThat(service.active()).containsExactlyElementsOf(tiers);
    }

    @Test
    void commissionFor_insideRange_returnsTierPct() {
        when(tierRepository.findByActiveTrueOrderByMinOrderCountAsc())
                .thenReturn(List.of(tier(0, 10, "5.00"), tier(11, 50, "8.00")));

        assertThat(service.commissionFor(20)).isEqualByComparingTo("8.00");
    }

    @Test
    void commissionFor_openEndedMax_acceptsAnyCountAboveMin() {
        when(tierRepository.findByActiveTrueOrderByMinOrderCountAsc())
                .thenReturn(List.of(tier(0, 10, "5.00"), tier(11, null, "8.00")));

        assertThat(service.commissionFor(1000)).isEqualByComparingTo("8.00");
    }

    @Test
    void commissionFor_upperBoundaryInclusive_returnsTierPct() {
        when(tierRepository.findByActiveTrueOrderByMinOrderCountAsc())
                .thenReturn(List.of(tier(0, 10, "5.00"), tier(11, 50, "8.00")));

        assertThat(service.commissionFor(10)).isEqualByComparingTo("5.00");
    }

    @Test
    void commissionFor_noMatchingTier_fallsBackToLowest() {
        when(tierRepository.findByActiveTrueOrderByMinOrderCountAsc())
                .thenReturn(List.of(tier(0, 5, "5.00"), tier(10, 20, "8.00")));

        assertThat(service.commissionFor(500)).isEqualByComparingTo("5.00");
    }

    @Test
    void commissionFor_noTiers_returnsZero() {
        when(tierRepository.findByActiveTrueOrderByMinOrderCountAsc()).thenReturn(List.of());

        assertThat(service.commissionFor(5)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
