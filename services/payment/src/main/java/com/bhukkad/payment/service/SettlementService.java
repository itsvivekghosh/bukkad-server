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
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class SettlementService {

    public static final BigDecimal DEFAULT_COMMISSION_RATE = new BigDecimal("0.20");

    private final SettlementRunRepository runRepository;
    private final RestaurantSettlementRepository settlementRepository;

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

        BigDecimal commission = grossAmount.multiply(DEFAULT_COMMISSION_RATE);
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