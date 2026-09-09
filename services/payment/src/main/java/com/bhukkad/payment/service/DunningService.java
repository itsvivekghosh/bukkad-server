package com.bhukkad.payment.service;

import com.bhukkad.payment.domain.DunningRun;
import com.bhukkad.payment.domain.DunningRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Dunning for failed payments (port of monolith {@code DunningService}):
 * schedules retry attempts and records them per payment.
 */
@Service
@RequiredArgsConstructor
public class DunningService {

    private final DunningRunRepository dunningRepository;

    @Transactional
    public DunningRun scheduleRetry(Long paymentId, int attempt, LocalDateTime scheduledAt) {
        DunningRun run = new DunningRun();
        run.setPaymentId(paymentId);
        run.setAttempt(attempt);
        run.setStatus("SCHEDULED");
        run.setScheduledAt(scheduledAt);
        return dunningRepository.save(run);
    }

    @Transactional(readOnly = true)
    public List<DunningRun> pending() {
        return dunningRepository.findByStatus("SCHEDULED");
    }
}