package com.bhukkad.admin.domain.service;
import com.bhukkad.admin.config.ComplianceProperties;

import com.bhukkad.admin.domain.repository.FraudEventRepository;
import com.bhukkad.admin.domain.repository.DataExportRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * Scheduled retention sweep for compliance-managed data (DPDP/GDPR).
 *
 * <p>Deletes artifacts past their configured retention window: expired data-export
 * payloads and aged fraud events (which qualify as personal data because they
 * carry IP addresses and device fingerprints).</p>
 *
 * <p>Each artifact type is purged in its own short transaction (via
 * {@link TransactionTemplate}, because self-invocation would bypass
 * {@code @Transactional}) so one large or failing delete never blocks the others.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataRetentionService {

    private final ComplianceProperties complianceProperties;
    private final DataExportRequestRepository dataExportRequestRepository;
    private final FraudEventRepository fraudEventRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * Fraud events older than the retention window are purged; they are abuse
     * signals, not financial records, so nothing downstream depends on them.
     */
    @Scheduled(fixedDelayString = "${app.compliance.retention-cleanup-ms:86400000}")
    @SchedulerLock(name = "compliance-retention-sweep", lockAtMostFor = "PT1H")
    public void purgeExpiredData() {
        if (!complianceProperties.isEnabled()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(complianceProperties.getRetentionDays());

        int exportRequests = purgeQuietly("data_export_requests", () ->
                (int) dataExportRequestRepository.deleteByCreatedAtBefore(cutoff));
        int fraudEvents = purgeQuietly("fraud_events", () ->
                (int) fraudEventRepository.deleteByCreatedAtBefore(cutoff));

        if (exportRequests > 0 || fraudEvents > 0) {
            log.info("RETENTION_PURGED | data_export_requests={} | fraud_events={} | cutoff={}",
                    exportRequests, fraudEvents, cutoff);
        }
    }

    private int purgeQuietly(String artifactType, RetentionPurge purge) {
        try {
            Integer removed = transactionTemplate.execute(status -> purge.run());
            return removed != null ? removed : 0;
        } catch (Exception ex) {
            log.warn("Retention purge failed for {}: {}", artifactType, ex.getMessage());
            return 0;
        }
    }

    @FunctionalInterface
    private interface RetentionPurge {
        int run();
    }
}
