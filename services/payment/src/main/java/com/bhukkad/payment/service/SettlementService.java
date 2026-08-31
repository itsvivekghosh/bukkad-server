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

/**
 * Restaurant settlement batch processing (Batch D depth). Runs on a schedule,
 * computing commission (default 20%) and net amounts per restaurant.
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    public static final BigDecimal DEFAULT_COMMISSION_RATE = new BigDecimal("0.20");

    private final SettlementRunRepository runRepository;
    private final RestaurantSettlementRepository settlementRepository;

    @Transactional
    public SettlementRun run(LocalDate runDate, Long restaurantId,
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
        settlementRepository.save(settlement);

        run.setTotalAmount(settlement.getNetAmount());
        run.setStatus("COMPLETED");
        return runRepository.save(run);
    }
}