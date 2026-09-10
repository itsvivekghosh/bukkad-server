package com.bhukkad.delivery.service;

import com.bhukkad.delivery.RiderLocationRetentionProperties;
import com.bhukkad.delivery.domain.RiderLocationUpdateRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Deterministic unit coverage for the rider-location retention sweep (perf
 * audit §2.3/§4.3): scheduler wiring (hourly cron + ShedLock single-runner
 * lock), the batched delete loop, and the never-kill-the-scheduler-thread
 * error contract. End-to-end purge semantics live in
 * {@code RiderLocationRetentionPostgresIntegrationTest} (Testcontainers);
 * these tests keep the fast suite honest without Docker.
 */
@ExtendWith(MockitoExtension.class)
class RiderLocationRetentionTest {

    @Mock private RiderLocationUpdateRepository locationRepository;
    @Mock private RiderLocationRetentionService retentionService;

    // ------------------------------------------------------------------
    // Scheduler wiring (G-2: if the lock or cron is removed, these fail).
    // ------------------------------------------------------------------

    @Test
    void hourlyPurge_isScheduledHourlyWithSingleRunnerLock() throws Exception {
        Method purge = RiderLocationRetentionScheduler.class.getDeclaredMethod("hourlyPurge");

        Scheduled scheduled = purge.getAnnotation(Scheduled.class);
        assertThat(scheduled).as("@Scheduled keeps the hourly cadence").isNotNull();
        assertThat(scheduled.cron()).as("cron default from properties")
                .isEqualTo("${app.rider-location-retention.purge-cron:0 15 * * * *}");

        SchedulerLock lock = purge.getAnnotation(SchedulerLock.class);
        assertThat(lock).as("@SchedulerLock keeps the purge single-runner "
                + "across the 3-replica deployment").isNotNull();
        assertThat(lock.name()).isEqualTo("rider-location-retention-purge");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT25M");
        assertThat(lock.lockAtLeastFor()).isEqualTo("PT1M");
    }

    @Test
    void hourlyPurge_delegatesToTheRetentionService() {
        RiderLocationRetentionScheduler scheduler =
                new RiderLocationRetentionScheduler(retentionService);
        when(retentionService.purgeExpired()).thenReturn(1200);

        scheduler.hourlyPurge();

        verify(retentionService).purgeExpired();
    }

    @Test
    void hourlyPurge_swallowsServiceFailure_soTheSchedulerThreadSurvives() {
        // A failed tick must never propagate to the @Scheduled thread (a thrown
        // exception can kill the cron runner): the scheduler logs and returns;
        // the next hourly tick retries because no state was persisted.
        RiderLocationRetentionScheduler scheduler = new RiderLocationRetentionScheduler(
                new RiderLocationRetentionService(locationRepository, new RiderLocationRetentionProperties()) {
                    @Override
                    public int purgeExpired() {
                        throw new IllegalStateException("db down");
                    }
                });

        assertThatCode(scheduler::hourlyPurge).doesNotThrowAnyException();
        verifyNoInteractions(locationRepository);
    }

    // ------------------------------------------------------------------
    // Batched delete loop.
    // ------------------------------------------------------------------

    @Test
    void purgeExpired_stopsWhenABatchComesBackShort() {
        RiderLocationRetentionService service =
                new RiderLocationRetentionService(locationRepository, new RiderLocationRetentionProperties());
        // 5000 + 300 rows: second batch short → drain complete, no third call.
        when(locationRepository.deleteOldestBefore(any(LocalDateTime.class), eq(5000)))
                .thenReturn(5000)
                .thenReturn(300);

        int deleted = service.purgeExpired();

        assertThat(deleted).isEqualTo(5300);
        verify(locationRepository, times(2)).deleteOldestBefore(any(LocalDateTime.class), eq(5000));
    }

    @Test
    void purgeExpired_capsAtMaxBatches_soOneTickCannotMonopoliseThePool() {
        RiderLocationRetentionProperties props = new RiderLocationRetentionProperties();
        props.setMaxBatches(3);
        RiderLocationRetentionService service =
                new RiderLocationRetentionService(locationRepository, props);
        // Every batch full: the cap ends the tick.
        when(locationRepository.deleteOldestBefore(any(LocalDateTime.class), eq(5000)))
                .thenReturn(5000);

        int deleted = service.purgeExpired();

        assertThat(deleted).isEqualTo(15000);
        verify(locationRepository, times(3)).deleteOldestBefore(any(LocalDateTime.class), eq(5000));
    }

    @Test
    void purgeExpired_nothingToPurge_isASingleProbe() {
        RiderLocationRetentionService service =
                new RiderLocationRetentionService(locationRepository, new RiderLocationRetentionProperties());
        when(locationRepository.deleteOldestBefore(any(LocalDateTime.class), eq(5000)))
                .thenReturn(0);

        int deleted = service.purgeExpired();

        assertThat(deleted).isZero();
        verify(locationRepository, times(1)).deleteOldestBefore(any(LocalDateTime.class), eq(5000));
    }

    @Test
    void purgeExpired_deletesAgainstTheRetentionCutoff() {
        RiderLocationRetentionProperties props = new RiderLocationRetentionProperties();
        props.setCutoffHours(24);
        RiderLocationRetentionService service =
                new RiderLocationRetentionService(locationRepository, props);
        when(locationRepository.deleteOldestBefore(any(LocalDateTime.class), eq(5000))).thenReturn(0);

        LocalDateTime before = LocalDateTime.now().minusHours(24);

        service.purgeExpired();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(locationRepository).deleteOldestBefore(cutoff.capture(), eq(5000));
        // Cutoff = now - 24h; allow 5s of clock skew between the two reads.
        assertThat(cutoff.getValue()).isAfter(before.minusSeconds(5));
        assertThat(cutoff.getValue()).isBefore(LocalDateTime.now().plusSeconds(5));
    }

    // ------------------------------------------------------------------
    // Declarative wiring guards.
    // ------------------------------------------------------------------

    @Test
    void purgeExpired_isTransactional() throws Exception {
        assertThat(RiderLocationRetentionService.class
                .getDeclaredMethod("purgeExpired")
                .isAnnotationPresent(Transactional.class))
                .as("each tick is one @Transactional unit")
                .isTrue();
    }

    @Test
    void serviceStaysCronFree_schedulerOwnsTheClock() {
        // The split keeps @Transactional batching on the service and cron
        // plumbing on the scheduler — assert it so a refactor cannot merge them.
        for (Method m : RiderLocationRetentionService.class.getDeclaredMethods()) {
            assertThat(m.getAnnotation(Scheduled.class))
                    .as("service must stay cron-free (scheduler owns @Scheduled): %s", m)
                    .isNull();
        }
    }
}