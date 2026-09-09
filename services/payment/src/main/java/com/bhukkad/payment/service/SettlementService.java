package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.RestaurantSettlement;
import com.bhukkad.payment.domain.RestaurantSettlementRepository;
import com.bhukkad.payment.domain.SettlementRun;
import com.bhukkad.payment.domain.SettlementRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class SettlementService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final SettlementRunRepository runRepository;
    private final RestaurantSettlementRepository settlementRepository;
    private final CommissionTierService commissionTierService;

    @Transactional
    public SettlementResult run(LocalDate runDate, Long restaurantId,
                                int orderCount, BigDecimal grossAmount) {
        if (orderCount < 0 || grossAmount.signum() < 0) {
            throw new BusinessException("Invalid settlement inputs");
        }
        SettlementRun run = new SettlementRun();
        run.setRunDate(runDate);
        run.setStatus("RUNNING");
        run = runRepository.save(run);

        // H-4: commission follows the configured tier for this order volume,
        // not a hardcoded 20%. commissionFor returns a PERCENT, so scale by
        // 100 with the platform-standard HALF_UP 2 rounding.
        BigDecimal commissionPct = commissionTierService.commissionFor(orderCount);
        BigDecimal commission = grossAmount.multiply(commissionPct)
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
        RestaurantSettlement settlement = new RestaurantSettlement();
        settlement.setSettlementRunId(run.getId());
        settlement.setRestaurantId(restaurantId);
        settlement.setOrderCount(orderCount);
        settlement.setGrossAmount(grossAmount);
        settlement.setCommission(commission);
        settlement.setNetAmount(grossAmount.subtract(commission));
        settlement.setStatus("PENDING");
        settlement = settlementRepository.save(settlement);

        run.setTotalAmount(settlement.getNetAmount());
        run.setStatus("COMPLETED");
        runRepository.save(run);

        return new SettlementResult(run, settlement);
    }

    public record SettlementResult(SettlementRun run, RestaurantSettlement settlement) {
    }
}