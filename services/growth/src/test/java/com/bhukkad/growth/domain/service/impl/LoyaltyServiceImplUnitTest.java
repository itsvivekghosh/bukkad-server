package com.bhukkad.growth.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.api.dto.response.LoyaltyPointsResponse;
import com.bhukkad.growth.domain.entity.LoyaltyPointBalance;
import com.bhukkad.growth.domain.repository.LoyaltyPointBalanceRepository;
import com.bhukkad.growth.domain.repository.LoyaltyPointsLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoyaltyServiceImplUnitTest {

    @Mock private LoyaltyPointsLedgerRepository ledgerRepository;
    @Mock private LoyaltyPointBalanceRepository balanceRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private final GrowthProperties properties = new GrowthProperties();
    private LoyaltyServiceImpl service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new LoyaltyServiceImpl(ledgerRepository, balanceRepository, redisTemplate, properties);
    }

    private LoyaltyPointBalance balance(long points, long lifetime) {
        LoyaltyPointBalance b = new LoyaltyPointBalance();
        b.setCustomerId(1L);
        b.setPoints(points);
        b.setLifetimePoints(lifetime);
        return b;
    }

    // ------------------------------------------------------------------
    // getLoyaltyPoints — cache read paths
    // ------------------------------------------------------------------

    @Test
    void getLoyaltyPoints_cacheHit_readsBalanceTierFromCacheValue() {
        when(valueOperations.get("loyalty:points:1")).thenReturn("2500");
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.of(balance(2500, 3000)));

        LoyaltyPointsResponse response = service.getLoyaltyPoints(1L);

        assertThat(response.getCurrentPoints()).isEqualTo(2500);
        assertThat(response.getLifetimePoints()).isEqualTo(3000);
        assertThat(response.getTierLevel()).isEqualTo(3);
        assertThat(response.getTierName()).isEqualTo("Silver");
        assertThat(response.getPointsToNextTier()).isEqualTo(2000);
    }

    @Test
    void getLoyaltyPoints_cacheMiss_rebuildsFromLedgerAndRewarms() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(ledgerRepository.ledgerBalance(1L)).thenReturn(6000L);
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.empty());
        when(ledgerRepository.ledgerLifetime(1L)).thenReturn(6000L);

        LoyaltyPointsResponse response = service.getLoyaltyPoints(1L);

        assertThat(response.getCurrentPoints()).isEqualTo(6000);
        assertThat(response.getTierLevel()).isEqualTo(4);
        assertThat(response.getTierName()).isEqualTo("Gold");
        assertThat(response.getPointsToNextTier()).isEqualTo(4000);
        verify(valueOperations).set(eq("loyalty:points:1"), eq("6000"), any(Duration.class));
    }

    @Test
    void getLoyaltyPoints_redisDown_stillServesFromLedger() {
        when(valueOperations.get(anyString()))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        when(ledgerRepository.ledgerBalance(1L)).thenReturn(500L);
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.of(balance(500, 700)));

        LoyaltyPointsResponse response = service.getLoyaltyPoints(1L);

        assertThat(response.getCurrentPoints()).isEqualTo(500);
        assertThat(response.getTierName()).isEqualTo("Bronze");
        assertThat(response.getLifetimePoints()).isEqualTo(700);
    }

    @Test
    void getLoyaltyPoints_rewarmFails_servesLedgerValueAnyway() {
        when(valueOperations.get(anyString())).thenReturn(null);
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        when(ledgerRepository.ledgerBalance(1L)).thenReturn(12000L);
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.of(balance(12000, 12000)));

        LoyaltyPointsResponse response = service.getLoyaltyPoints(1L);

        assertThat(response.getCurrentPoints()).isEqualTo(12000);
        assertThat(response.getTierName()).isEqualTo("Platinum");
        assertThat(response.getPointsToNextTier()).isZero();
    }

    @Test
    void getLoyaltyPoints_memberTierBelowFloor() {
        when(valueOperations.get(anyString())).thenReturn("100");
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.of(balance(100, 499)));

        LoyaltyPointsResponse response = service.getLoyaltyPoints(1L);

        assertThat(response.getTierLevel()).isEqualTo(1);
        assertThat(response.getTierName()).isEqualTo("Member");
        assertThat(response.getPointsToNextTier()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // creditPoints
    // ------------------------------------------------------------------

    @Test
    void creditPoints_threeArgOverload_appendsLedgerWithoutReference() {
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.of(balance(100, 100)));

        service.creditPoints(1L, 100, "WELCOME");

        verify(ledgerRepository).save(any());
        verify(balanceRepository).credit(1L, 100);
        verify(valueOperations).set(eq("loyalty:points:1"), eq("100"), any(Duration.class));
    }

    @Test
    void creditPoints_nonPositive_throws() {
        assertThatThrownBy(() -> service.creditPoints(1L, 0, "X"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void creditPoints_overCeiling_throws() {
        assertThatThrownBy(() -> service.creditPoints(1L, 100_001, "X"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ceiling");
    }

    @Test
    void creditPoints_replayWithExistingReference_isNoop() {
        when(ledgerRepository.existsByReferenceId("dup-key")).thenReturn(true);

        service.creditPoints(1L, 100, "X", "dup-key");

        verify(ledgerRepository, never()).save(any());
        verify(balanceRepository, never()).credit(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void creditPoints_writeThroughSkippedWhenRowMissing() {
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.empty());

        service.creditPoints(1L, 100, "X", null);

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void creditPoints_writeThroughRedisFailureSwallowed() {
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.of(balance(10, 10)));
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        service.creditPoints(1L, 100, "X", null);

        verify(balanceRepository).credit(1L, 100);
    }

    // ------------------------------------------------------------------
    // redeemPoints
    // ------------------------------------------------------------------

    @Test
    void redeemPoints_nonPositive_returnsFalse() {
        assertThat(service.redeemPoints(1L, -5)).isFalse();
    }

    @Test
    void redeemPoints_belowMinimumThreshold_returnsFalse() {
        assertThat(service.redeemPoints(1L, 50)).isFalse();
        verify(balanceRepository, never()).materializeFromLedger(any());
    }

    @Test
    void redeemPoints_insufficientBalance_returnsFalse() {
        when(balanceRepository.redeem(1L, 100)).thenReturn(0);

        assertThat(service.redeemPoints(1L, 100)).isFalse();

        verify(balanceRepository).materializeFromLedger(1L);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void redeemPoints_success_appendsDebitAndRefreshesCache() {
        when(balanceRepository.redeem(1L, 200)).thenReturn(1);
        when(balanceRepository.findByCustomerId(1L)).thenReturn(Optional.of(balance(800, 1000)));

        assertThat(service.redeemPoints(1L, 200)).isTrue();

        verify(ledgerRepository).save(any());
        verify(valueOperations).set("loyalty:points:1", "800", Duration.ofHours(24));
    }

    // ------------------------------------------------------------------
    // calculatePointsForOrder
    // ------------------------------------------------------------------

    @Test
    void calculatePointsForOrder_floorsFractionalPoints() {
        assertThat(service.calculatePointsForOrder(99.99)).isEqualTo(99);
    }

    @Test
    void calculatePointsForOrder_usesConfiguredRate() {
        properties.getLoyalty().setPointsPerRupee(2);

        assertThat(service.calculatePointsForOrder(50.0)).isEqualTo(100);
    }
}
