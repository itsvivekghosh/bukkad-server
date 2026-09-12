package com.bhukkad.growth.domain.service.impl;

import com.bhukkad.common.error.UpstreamUnavailableException;
import com.bhukkad.growth.api.dto.response.ReferralStatsResponse;
import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.domain.entity.ReferralRecord;
import com.bhukkad.growth.domain.repository.LoyaltyPointBalanceRepository;
import com.bhukkad.growth.domain.repository.LoyaltyPointsLedgerRepository;
import com.bhukkad.growth.domain.repository.ReferralRecordRepository;
import com.bhukkad.growth.infrastructure.client.ReferralServiceClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReferralTrackingServiceImplTest {

    @Mock private ReferralRecordRepository referralRecordRepository;
    @Mock private LoyaltyPointsLedgerRepository ledgerRepository;
    @Mock private LoyaltyPointBalanceRepository balanceRepository;
    @Mock private ReferralServiceClient referralServiceClient;

    private final GrowthProperties properties = new GrowthProperties();

    private ReferralTrackingServiceImpl service() {
        return new ReferralTrackingServiceImpl(referralRecordRepository, ledgerRepository,
                balanceRepository, referralServiceClient, properties);
    }

    @Test
    void getReferralStats_projectsCountersFromTrackingTable() {
        when(referralRecordRepository.countByReferrerId(7L)).thenReturn(3L);

        ReferralStatsResponse stats = service().getReferralStats(7L);

        assertThat(stats.getCustomerId()).isEqualTo(7L);
        assertThat(stats.getTotalReferrals()).isEqualTo(3);
        assertThat(stats.getSuccessfulReferrals()).isEqualTo(3);
        assertThat(stats.getReferrerRewardEarned()).isEqualTo(150); // 3 x default 50
        assertThat(stats.getReferredUserRewardEarned()).isEqualTo(100);
    }

    @Test
    void generateReferralCode_delegatesToReferralService() {
        when(referralServiceClient.generateCode(9L)).thenReturn("BK9");

        assertThat(service().generateReferralCode(9L)).isEqualTo("BK9");
    }

    @Test
    void generateReferralCode_upstreamDown_throws503Mapped() {
        when(referralServiceClient.generateCode(9L)).thenReturn(null);

        assertThatThrownBy(() -> service().generateReferralCode(9L))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void applyReferralReward_existingRecord_returnsFalse() {
        when(referralRecordRepository.existsByReferredCustomerId(2L)).thenReturn(true);

        assertThat(service().applyReferralReward(1L, 2L)).isFalse();
        verify(referralRecordRepository, never()).save(any());
    }

    @Test
    void applyReferralReward_firstApply_creditsLoyaltyOnce() {
        when(referralRecordRepository.existsByReferredCustomerId(2L)).thenReturn(false);
        when(referralRecordRepository.save(any(ReferralRecord.class))).thenAnswer(inv -> {
            ReferralRecord r = inv.getArgument(0);
            r.setId(501L);
            return r;
        });

        assertThat(service().applyReferralReward(1L, 2L)).isTrue();

        ArgumentCaptor<ReferralRecord> saved = ArgumentCaptor.forClass(ReferralRecord.class);
        verify(referralRecordRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        ReferralRecord first = saved.getAllValues().get(0);
        assertThat(first.getReferrerId()).isEqualTo(1L);
        assertThat(first.getReferredCustomerId()).isEqualTo(2L);
        assertThat(first.getStatus()).isEqualTo(ReferralRecord.STATUS_COMPLETED);
        assertThat(first.getReferralCode()).startsWith("BKR1");
        assertThat(saved.getAllValues().get(1).isRewardCredited()).isTrue();
        assertThat(saved.getAllValues().get(1).getCompletedAt()).isNotNull();
        verify(ledgerRepository).save(any());
        verify(balanceRepository).credit(1L, 50);
    }

    @Test
    void applyReferralReward_zeroReward_skipsLedgerButCompletes() {
        properties.getReferral().setReferrerReward(0);
        when(referralRecordRepository.existsByReferredCustomerId(2L)).thenReturn(false);
        when(referralRecordRepository.save(any(ReferralRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service().applyReferralReward(1L, 2L)).isTrue();

        verify(ledgerRepository, never()).save(any());
        verify(balanceRepository, never()).credit(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void applyReferralReward_losesUniqueRace_returnsFalse() {
        when(referralRecordRepository.existsByReferredCustomerId(2L)).thenReturn(false);
        when(referralRecordRepository.save(any(ReferralRecord.class)))
                .thenThrow(new DataIntegrityViolationException("uq_referral_records_referred_customer"));

        assertThat(service().applyReferralReward(1L, 2L)).isFalse();

        verify(ledgerRepository, never()).save(any());
    }
}
