package com.bhukkad.delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly retention sweep over expired rider location pings (perf audit
 * §2.3/§4.3: the table grew unbounded). {@code @SchedulerLock} keeps it
 * single-runner across the 3-replica deployment; batch sizing / cutoff /
 * per-tick cap live in {@code RiderLocationRetentionProperties} and the
 * {@code app.rider-location-retention.*} keys. The batched delete loop is in
 * {@link RiderLocationRetentionService}. Gated by
 * {@code app.rider-location-retention.enabled} (default ON).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.rider-location-retention", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RiderLocationRetentionScheduler {

    private final RiderLocationRetentionService retentionService;

    /** Offset to :15 so the sweep never lands on the top-of-hour poll peak. */
    @Scheduled(cron = "${app.rider-location-retention.purge-cron:0 15 * * * *}")
    @SchedulerLock(name = "rider-location-retention-purge",
            lockAtMostFor = "PT25M", lockAtLeastFor = "PT1M")
    public void hourlyPurge() {
        try {
            retentionService.purgeExpired();
        } catch (Exception ex) {
            // A failed tick must never kill the scheduler thread; the next
            // hourly tick retries because no state was persisted here.
            log.error("RIDER_LOCATION_RETENTION_FAILED | error={}", ex.toString(), ex);
        }
    }
}
