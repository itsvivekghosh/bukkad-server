package com.bhukkad.growth.domain.service;

import com.bhukkad.growth.domain.entity.LoyaltyPointBalance;
import com.bhukkad.growth.domain.repository.LoyaltyPointBalanceRepository;
import com.bhukkad.growth.domain.service.LoyaltyReconciliationService.ReconciliationReport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoyaltyReconciliationServiceUnitTest {

    @Mock private LoyaltyPointBalanceRepository balanceRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private MeterRegistry meterRegistry;
    @Mock private Counter driftCounter;

    private LoyaltyReconciliationService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new LoyaltyReconciliationService(balanceRepository, redisTemplate, meterRegistry);
    }

    private static LoyaltyPointBalanceRepository.DriftProjection drift(Long customerId, long drift) {
        return new LoyaltyPointBalanceRepository.DriftProjection() {
            @Override public Long getCustomerId() { return customerId; }
            @Override public long getDrift() { return drift; }
        };
    }

    @Test
    void reconcile_cleanRun_returnsZeroDriftAndNoMetric() {
        when(balanceRepository.findDriftedBalances()).thenReturn(List.of());
        when(balanceRepository.reconcileAllFromLedger()).thenReturn(0);

        ReconciliationReport report = service.reconcile();

        assertThat(report.driftedCustomers()).isZero();
        assertThat(report.totalDriftPoints()).isZero();
        verify(meterRegistry, never()).counter(anyString());
    }

    @Test
    void reconcile_drift_correctsAndCountsAndRefreshesCache() {
        when(balanceRepository.findDriftedBalances())
                .thenReturn(List.of(drift(1L, -30L), drift(2L, 40L)));
        when(balanceRepository.reconcileAllFromLedger()).thenReturn(2);
        LoyaltyPointBalance b = new LoyaltyPointBalance();
        b.setCustomerId(1L);
        b.setPoints(120);
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.of(b));
        when(balanceRepository.findByCustomerId(2L)).thenReturn(Optional.empty());
        when(meterRegistry.counter(LoyaltyReconciliationService.DRIFT_METRIC)).thenReturn(driftCounter);

        ReconciliationReport report = service.reconcile();

        assertThat(report.correctedRows()).isEqualTo(2);
        assertThat(report.driftedCustomers()).isEqualTo(2);
        assertThat(report.totalDriftPoints()).isEqualTo(70);
        verify(driftCounter).increment(70);
        verify(valueOperations).set(eq("loyalty:points:1"), eq("120"), any(Duration.class));
    }

    @Test
    void reconcile_cacheRefreshFailure_doesNotAbortCorrection() {
        when(balanceRepository.findDriftedBalances()).thenReturn(List.of(drift(1L, 5L)));
        when(balanceRepository.reconcileAllFromLedger()).thenReturn(1);
        when(meterRegistry.counter(LoyaltyReconciliationService.DRIFT_METRIC)).thenReturn(driftCounter);
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        LoyaltyPointBalance b = new LoyaltyPointBalance();
        b.setPoints(9);
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.of(b));

        ReconciliationReport report = service.reconcile();

        assertThat(report.totalDriftPoints()).isEqualTo(5);
    }

    @Test
    void reconcile_withoutMeterRegistry_stillCorrects() {
        LoyaltyReconciliationService noRegistry =
                new LoyaltyReconciliationService(balanceRepository, redisTemplate, null);
        when(balanceRepository.findDriftedBalances()).thenReturn(List.of(drift(4L, 7L)));
        when(balanceRepository.reconcileAllFromLedger()).thenReturn(1);
        when(balanceRepository.findByCustomerId(4L)).thenReturn(Optional.empty());

        assertThat(noRegistry.reconcile().driftedCustomers()).isEqualTo(1);
    }
}
