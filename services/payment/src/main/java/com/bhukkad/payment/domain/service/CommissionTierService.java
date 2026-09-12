package com.bhukkad.payment.domain.service;

import com.bhukkad.payment.domain.entity.CommissionTier;
import com.bhukkad.payment.domain.repository.CommissionTierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bhukkad.payment.domain.entity.CommissionTier;
import java.math.BigDecimal;
import java.util.List;

/**
 * Tiered commission for restaurants (port of monolith
 * {@code CommissionTierServiceImpl}).
 */
@Service
@RequiredArgsConstructor
public class CommissionTierService {

    private final CommissionTierRepository tierRepository;

    @Transactional
    public CommissionTier create(int minOrderCount, Integer maxOrderCount, BigDecimal commissionPct) {
        CommissionTier tier = new CommissionTier();
        tier.setMinOrderCount(minOrderCount);
        tier.setMaxOrderCount(maxOrderCount);
        tier.setCommissionPct(commissionPct);
        return tierRepository.save(tier);
    }

    @Transactional(readOnly = true)
    public List<CommissionTier> active() {
        return tierRepository.findByActiveTrueOrderByMinOrderCountAsc();
    }

    /** Finds the tier containing the given order count; falls back to the lowest. */
    @Transactional(readOnly = true)
    public BigDecimal commissionFor(int orderCount) {
        return tierRepository.findByActiveTrueOrderByMinOrderCountAsc().stream()
                .filter(t -> t.getMinOrderCount() <= orderCount
                        && (t.getMaxOrderCount() == null || orderCount <= t.getMaxOrderCount()))
                .findFirst()
                .map(CommissionTier::getCommissionPct)
                .orElseGet(() -> tierRepository.findByActiveTrueOrderByMinOrderCountAsc()
                        .stream().findFirst().map(CommissionTier::getCommissionPct).orElse(BigDecimal.ZERO));
    }
}