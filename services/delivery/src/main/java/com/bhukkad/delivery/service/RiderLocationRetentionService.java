package com.bhukkad.delivery.service;

import com.bhukkad.delivery.RiderLocationRetentionProperties;
import com.bhukkad.delivery.domain.RiderLocationUpdateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Batched delete of expired rider location pings (perf audit §2.3:
 * {@code rider_location_updates} grows unbounded). Chunked deletes keep row
 * locks short; the hourly entrypoint lives in
 * {@link RiderLocationRetentionScheduler}. Deleting the same window twice is
 * harmless, so overlapping runs are safe even before locking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderLocationRetentionService {

    private final RiderLocationUpdateRepository locationRepository;
    private final RiderLocationRetentionProperties properties;

    /**
     * Purges stale pings and returns the number of rows deleted (capped by
     * {@code maxBatches} so one tick can never monopolise the pool).
     */
    @Transactional
    public int purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(properties.getCutoffHours());
        int deleted = 0;
        for (int batch = 0; batch < properties.getMaxBatches(); batch++) {
            int removed = locationRepository.deleteOldestBefore(cutoff, properties.getBatchSize());
            deleted += removed;
            if (removed < properties.getBatchSize()) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("RIDER_LOCATION_PURGE | deleted={} | cutoff={}", deleted, cutoff);
        } else {
            log.debug("RIDER_LOCATION_PURGE | nothing older than cutoff={}", cutoff);
        }
        return deleted;
    }
}
