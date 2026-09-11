package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.RiderEarning;
import com.bhukkad.payment.domain.RiderEarningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Rider earnings money path (audit V-02 finish — extracted from
 * {@code DeliveryPaymentController} alongside {@link CodWalletService} so no
 * transaction demarcation stays on the HTTP layer). Guards are the verified
 * controller behavior, moved verbatim: positive-amount validation, the
 * per-delivery cap, (agentId, orderId) idempotency and the one-way
 * EARNED → PAID transition.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderEarningService {

    /** Guards against fat-fingered or fabricated single-earning amounts. */
    private static final BigDecimal MAX_EARNING_AMOUNT = new BigDecimal("10000.00");

    private final RiderEarningRepository earningRepository;

    /** Outcome of a record attempt: {@code duplicate} means nothing was written. */
    public record EarningResult(RiderEarning earning, boolean duplicate) {
    }

    @Transactional
    public EarningResult record(Long agentId, Long orderId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("Earning amount must be positive");
        }
        if (amount.compareTo(MAX_EARNING_AMOUNT) > 0) {
            throw new BusinessException("Earning amount exceeds the per-delivery limit");
        }
        // Idempotent by (agentId, orderId): a retry, replay, or duplicate
        // dispatch previously minted a second payable earning (backstopped
        // by uq_rider_earnings_agent_order, V2).
        if (earningRepository.countByAgentIdAndOrderId(agentId, orderId) > 0) {
            return new EarningResult(null, true);
        }

        RiderEarning earning = new RiderEarning();
        earning.setAgentId(agentId);
        earning.setOrderId(orderId);
        earning.setAmount(amount);
        earning.setStatus("EARNED");
        earning = earningRepository.save(earning);

        log.info("Recorded rider earning: agentId={}, orderId={}, amount={}", agentId, orderId, amount);
        return new EarningResult(earning, false);
    }

    @Transactional(readOnly = true)
    public List<RiderEarning> listByAgent(Long agentId) {
        return earningRepository.findByAgentId(agentId);
    }

    @Transactional
    public void markPaid(Long earningId) {
        // Guarded transition: EARNED → PAID only. A replay of this call used
        // to re-flip WITHHELD rows to PAID with no approval trail.
        int updated = earningRepository.markPaidIfEarned(earningId);
        if (updated == 0) {
            throw new BusinessException(
                    "Earning " + earningId + " is not in an payable (EARNED) state");
        }

        log.info("Marked earning as paid: earningId={}", earningId);
    }
}
