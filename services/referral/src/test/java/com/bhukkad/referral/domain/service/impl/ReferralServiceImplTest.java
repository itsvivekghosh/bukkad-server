package com.bhukkad.referral.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.ratelimit.RateLimitDecision;
import com.bhukkad.common.ratelimit.RateLimitExceededException;
import com.bhukkad.common.ratelimit.RateLimitService;
import com.bhukkad.referral.api.dto.response.ReferralInfoResponse;
import com.bhukkad.referral.config.ReferralAbuseProperties;
import com.bhukkad.referral.config.ReferralServiceProperties;
import com.bhukkad.referral.domain.entity.ReferralRewardLedger;
import com.bhukkad.referral.domain.entity.UserReferralCode;
import com.bhukkad.referral.domain.repository.ReferralRewardLedgerRepository;
import com.bhukkad.referral.domain.repository.UserReferralCodeRepository;
import com.bhukkad.referral.domain.service.ReferralService.ApplyReferralOutcome;
import com.bhukkad.referral.domain.service.ReferralService.CompletionOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferralServiceImplTest {

    @Mock private UserReferralCodeRepository referralCodeRepository;
    @Mock private ReferralRewardLedgerRepository rewardLedgerRepository;
    @Mock private RateLimitService rateLimitService;

    private final ReferralServiceProperties properties = new ReferralServiceProperties();
    private final ReferralAbuseProperties abuseProperties = new ReferralAbuseProperties();

    private ReferralServiceImpl service() {
        return new ReferralServiceImpl(referralCodeRepository, rewardLedgerRepository,
                properties, rateLimitService, abuseProperties);
    }

    private static UserReferralCode row(Long customerId, String code, Long referredBy) {
        UserReferralCode r = new UserReferralCode();
        r.setCustomerId(customerId);
        r.setReferralCode(code);
        r.setReferredBy(referredBy);
        r.setReferralBonusEarned(75.0);
        return r;
    }

    private void allowRateLimit() {
        when(rateLimitService.check(anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(new RateLimitDecision(true, 1, 5, 0));
    }

    // ------------------------------------------------------------------
    // getReferralInfo
    // ------------------------------------------------------------------

    @Test
    void getReferralInfo_nullCustomerId_throws() {
        assertThatThrownBy(() -> service().getReferralInfo(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("required");
    }

    @Test
    void getReferralInfo_existingCode_returnsSummary() {
        when(referralCodeRepository.findByCustomerId(7L))
                .thenReturn(Optional.of(row(7L, "BKABC12345", null)));
        when(referralCodeRepository.countByReferredBy(7L)).thenReturn(3L);

        ReferralInfoResponse info = service().getReferralInfo(7L);

        assertThat(info.referralCode()).isEqualTo("BKABC12345");
        assertThat(info.referralsCount()).isEqualTo(3);
        assertThat(info.referralBonusEarned()).isEqualTo(75.0);
    }

    @Test
    void getReferralInfo_existingRowWithoutCode_throws() {
        when(referralCodeRepository.findByCustomerId(7L))
                .thenReturn(Optional.of(row(7L, null, null)));

        assertThatThrownBy(() -> service().getReferralInfo(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not assigned");
    }

    @Test
    void getReferralInfo_missingRow_createsRowThenReturnsCode() {
        when(referralCodeRepository.findByCustomerId(7L)).thenReturn(
                Optional.empty(), Optional.of(row(7L, "BKNEWCODE1", null)));
        when(referralCodeRepository.insertReferralRow(eq(7L), anyString(), isNull())).thenReturn(1);
        when(referralCodeRepository.countByReferredBy(7L)).thenReturn(0L);

        ReferralInfoResponse info = service().getReferralInfo(7L);

        assertThat(info.referralCode()).isEqualTo("BKNEWCODE1");
        verify(referralCodeRepository).insertReferralRow(eq(7L), anyString(), isNull());
    }

    // ------------------------------------------------------------------
    // isValidReferralCode
    // ------------------------------------------------------------------

    @Test
    void isValidReferralCode_blank_returnsFalse() {
        assertThat(service().isValidReferralCode("  ")).isFalse();
        assertThat(service().isValidReferralCode(null)).isFalse();
    }

    @Test
    void isValidReferralCode_normalizesAndLooksUp() {
        when(referralCodeRepository.findByReferralCode("BKABC12345"))
                .thenReturn(Optional.of(row(7L, "BKABC12345", null)));

        assertThat(service().isValidReferralCode(" bkabc12345 ")).isTrue();
    }

    @Test
    void isValidReferralCode_unknown_returnsFalse() {
        when(referralCodeRepository.findByReferralCode("BKNOPE0000")).thenReturn(Optional.empty());

        assertThat(service().isValidReferralCode("BKNOPE0000")).isFalse();
    }

    // ------------------------------------------------------------------
    // generateAndSaveReferralCode
    // ------------------------------------------------------------------

    @Test
    void generateAndSaveReferralCode_nullId_throws() {
        assertThatThrownBy(() -> service().generateAndSaveReferralCode(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void generateAndSaveReferralCode_existingCode_returnsIt() {
        when(referralCodeRepository.findByCustomerId(7L))
                .thenReturn(Optional.of(row(7L, "BKEXISTING", null)));

        assertThat(service().generateAndSaveReferralCode(7L)).isEqualTo("BKEXISTING");
        verify(referralCodeRepository, never()).assignCode(anyLong(), anyString());
    }

    @Test
    void generateAndSaveReferralCode_blankCode_assignsUniqueCode() {
        when(referralCodeRepository.findByCustomerId(7L)).thenReturn(
                Optional.of(row(7L, "", null)),
                Optional.of(row(7L, "BKASSIGNED", null)));
        when(referralCodeRepository.assignCode(eq(7L), anyString())).thenReturn(0, 1);

        assertThat(service().generateAndSaveReferralCode(7L)).isEqualTo("BKASSIGNED");
        verify(referralCodeRepository, times(2)).assignCode(eq(7L), anyString());
    }

    @Test
    void generateAndSaveReferralCode_assignNeverSucceeds_throwsAfterAttempts() {
        when(referralCodeRepository.findByCustomerId(7L)).thenReturn(Optional.of(row(7L, null, null)));
        when(referralCodeRepository.assignCode(eq(7L), anyString())).thenReturn(0);

        assertThatThrownBy(() -> service().generateAndSaveReferralCode(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unique referral code");
        verify(referralCodeRepository, times(25)).assignCode(eq(7L), anyString());
    }

    @Test
    void generateAndSaveReferralCode_createdByConcurrentTwin_isAccepted() {
        when(referralCodeRepository.findByCustomerId(7L)).thenReturn(
                Optional.empty(), Optional.of(row(7L, "BKTWIN0000", null)));
        when(referralCodeRepository.insertReferralRow(eq(7L), anyString(), isNull())).thenReturn(0);
        when(referralCodeRepository.countByReferredBy(7L)).thenReturn(0L);

        assertThat(service().getReferralInfo(7L).referralCode()).isEqualTo("BKTWIN0000");
    }

    @Test
    void generateAndSaveReferralCode_creationNeverSucceeds_throws() {
        ReferralServiceImpl service = service();
        when(referralCodeRepository.findByCustomerId(7L)).thenReturn(Optional.empty());
        when(referralCodeRepository.insertReferralRow(eq(7L), anyString(), isNull())).thenReturn(0);

        assertThatThrownBy(() -> service.generateAndSaveReferralCode(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unique referral code");
        verify(referralCodeRepository, times(25)).insertReferralRow(eq(7L), anyString(), isNull());
    }

    // ------------------------------------------------------------------
    // applyReferral
    // ------------------------------------------------------------------

    @Test
    void applyReferral_nullInputs_returnsNotApplied() {
        ReferralServiceImpl service = service();

        assertThat(service.applyReferral(null, "a@b.c", "BKX").applied()).isFalse();
        assertThat(service.applyReferral(1L, "a@b.c", " ").applied()).isFalse();
    }

    @Test
    void applyReferral_rateLimited_throws() {
        when(rateLimitService.check(anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(RateLimitDecision.denied(6, 5, 100));
        ReferralServiceImpl service = service();

        assertThatThrownBy(() -> service.applyReferral(1L, "a@b.c", "BKCODE1111"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void applyReferral_unknownCode_throws() {
        allowRateLimit();
        ReferralServiceImpl service = service();
        when(referralCodeRepository.findByCustomerId(2L))
                .thenReturn(Optional.of(row(2L, "BKREFME000", null)));
        when(referralCodeRepository.findByReferralCode("BKBADCODE0")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyReferral(2L, "a@b.c", "BKBADCODE0"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid referral code");
    }

    @Test
    void applyReferral_ownCode_throws() {
        allowRateLimit();
        ReferralServiceImpl service = service();
        when(referralCodeRepository.findByCustomerId(2L))
                .thenReturn(Optional.of(row(2L, "BKREFME000", null)));
        when(referralCodeRepository.findByReferralCode("BKSELF00000"))
                .thenReturn(Optional.of(row(2L, "BKSELF00000", null)));

        assertThatThrownBy(() -> service.applyReferral(2L, "a@b.c", "bkself00000"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("own referral code");
    }

    @Test
    void applyReferral_firstBinding_creditsRewardOnce() {
        allowRateLimit();
        ReferralServiceImpl service = service();
        when(referralCodeRepository.findByCustomerId(2L))
                .thenReturn(Optional.of(row(2L, "BKREFME000", null)));
        when(referralCodeRepository.findByReferralCode("BKREFERRER"))
                .thenReturn(Optional.of(row(9L, "BKREFERRER", null)));
        when(referralCodeRepository.bindReferrer(2L, 9L)).thenReturn(1);
        when(rewardLedgerRepository.insertRewardIfAbsent(9L, 2L, ReferralRewardLedger.TYPE_APPLY_BONUS,
                50.0, ReferralRewardLedger.EVENT_APPLY, "apply:2", null)).thenReturn(1);

        ApplyReferralOutcome outcome = service.applyReferral(2L, "a@b.c", "  bkreferrer ");

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.firstBinding()).isTrue();
        assertThat(outcome.referrerCustomerId()).isEqualTo(9L);
        verify(referralCodeRepository).incrementReferralsCount(9L);
        verify(rewardLedgerRepository).addBonusEarned(9L, 50.0);
    }

    @Test
    void applyReferral_rewardReplay_creditsBonusEarnedOnlyOnce() {
        allowRateLimit();
        ReferralServiceImpl service = service();
        when(referralCodeRepository.findByCustomerId(2L))
                .thenReturn(Optional.of(row(2L, "BKREFME000", null)));
        when(referralCodeRepository.findByReferralCode("BKREFERRER"))
                .thenReturn(Optional.of(row(9L, "BKREFERRER", null)));
        when(referralCodeRepository.bindReferrer(2L, 9L)).thenReturn(1);
        when(rewardLedgerRepository.insertRewardIfAbsent(anyLong(), anyLong(), anyString(),
                anyDouble(), anyString(), anyString(), isNull())).thenReturn(0);

        ApplyReferralOutcome outcome = service.applyReferral(2L, "a@b.c", "BKREFERRER");

        assertThat(outcome.firstBinding()).isTrue();
        verify(rewardLedgerRepository, never()).addBonusEarned(anyLong(), anyDouble());
    }

    @Test
    void applyReferral_zeroBonus_skipsLedgerInsert() {
        properties.setApplyBonusAmount(0);
        allowRateLimit();
        ReferralServiceImpl service = service();
        when(referralCodeRepository.findByCustomerId(2L))
                .thenReturn(Optional.of(row(2L, "BKREFME000", null)));
        when(referralCodeRepository.findByReferralCode("BKREFERRER"))
                .thenReturn(Optional.of(row(9L, "BKREFERRER", null)));
        when(referralCodeRepository.bindReferrer(2L, 9L)).thenReturn(1);

        assertThat(service.applyReferral(2L, "a@b.c", "BKREFERRER").firstBinding()).isTrue();
        verify(rewardLedgerRepository, never())
                .insertRewardIfAbsent(anyLong(), anyLong(), anyString(), anyDouble(),
                        anyString(), anyString(), any());
    }

    @Test
    void applyReferral_alreadyBound_reportsExistingReferrer() {
        allowRateLimit();
        ReferralServiceImpl service = service();
        when(referralCodeRepository.findByCustomerId(2L)).thenReturn(
                Optional.of(row(2L, "BKREFME000", null)),
                Optional.of(row(2L, "BKREFME000", 42L)));
        when(referralCodeRepository.findByReferralCode("BKREFERRER"))
                .thenReturn(Optional.of(row(9L, "BKREFERRER", null)));
        when(referralCodeRepository.bindReferrer(2L, 9L)).thenReturn(0);

        ApplyReferralOutcome outcome = service.applyReferral(2L, "a@b.c", "BKREFERRER");

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.firstBinding()).isFalse();
        assertThat(outcome.referrerCustomerId()).isEqualTo(42L);
        verify(referralCodeRepository, never()).incrementReferralsCount(anyLong());
    }

    @Test
    void applyReferral_alreadyBoundWithoutRow_returnsNullReferrer() {
        allowRateLimit();
        ReferralServiceImpl service = service();
        when(referralCodeRepository.findByCustomerId(2L)).thenReturn(
                Optional.of(row(2L, "BKREFME000", null)),
                Optional.empty());
        when(referralCodeRepository.findByReferralCode("BKREFERRER"))
                .thenReturn(Optional.of(row(9L, "BKREFERRER", null)));
        when(referralCodeRepository.bindReferrer(2L, 9L)).thenReturn(0);

        ApplyReferralOutcome outcome = service.applyReferral(2L, "a@b.c", "BKREFERRER");

        assertThat(outcome.firstBinding()).isFalse();
        assertThat(outcome.referrerCustomerId()).isNull();
    }

    // ------------------------------------------------------------------
    // completeReferral
    // ------------------------------------------------------------------

    @Test
    void completeReferral_nullId_throws() {
        assertThatThrownBy(() -> service().completeReferral(null, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void completeReferral_noRecord_throws() {
        ReferralServiceImpl service = service();
        when(referralCodeRepository.findByCustomerId(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeReferral(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no referral record");
    }

    @Test
    void completeReferral_notBound_returnsNoBinding() {
        ReferralServiceImpl service = service();
        when(referralCodeRepository.findByCustomerId(2L))
                .thenReturn(Optional.of(row(2L, "BKREFME000", null)));

        CompletionOutcome outcome = service.completeReferral(2L, 1L);

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.referrerCustomerId()).isNull();
    }

    @Test
    void completeReferral_firstCompletion_creditsBonus() {
        ReferralServiceImpl service = service();
        when(referralCodeRepository.findByCustomerId(2L))
                .thenReturn(Optional.of(row(2L, "BKREFME000", 9L)));
        when(rewardLedgerRepository.insertRewardIfAbsent(9L, 2L, ReferralRewardLedger.TYPE_COMPLETION_BONUS,
                25.0, ReferralRewardLedger.EVENT_COMPLETE, "order:55", 55L)).thenReturn(1);

        CompletionOutcome outcome = service.completeReferral(2L, 55L);

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.referrerCustomerId()).isEqualTo(9L);
        verify(rewardLedgerRepository).addBonusEarned(9L, 25.0);
    }

    @Test
    void completeReferral_replay_returnsAlreadyCompleted() {
        ReferralServiceImpl service = service();
        when(referralCodeRepository.findByCustomerId(2L))
                .thenReturn(Optional.of(row(2L, "BKREFME000", 9L)));
        when(rewardLedgerRepository.insertRewardIfAbsent(anyLong(), anyLong(), anyString(),
                anyDouble(), anyString(), anyString(), isNull())).thenReturn(0);

        CompletionOutcome outcome = service.completeReferral(2L, null);

        assertThat(outcome.completed()).isFalse();
        verify(rewardLedgerRepository, never()).addBonusEarned(anyLong(), anyDouble());
    }

    @Test
    void completeReferral_nullOrder_usesCustomerIdAsEventKey() {
        ReferralServiceImpl service = service();
        when(referralCodeRepository.findByCustomerId(2L))
                .thenReturn(Optional.of(row(2L, "BKREFME000", 9L)));
        when(rewardLedgerRepository.insertRewardIfAbsent(9L, 2L, ReferralRewardLedger.TYPE_COMPLETION_BONUS,
                25.0, ReferralRewardLedger.EVENT_COMPLETE, "order:2", null)).thenReturn(1);

        assertThat(service.completeReferral(2L, null).completed()).isTrue();
    }

    @Test
    void completeReferral_zeroCompletionBonus_skipsLedger() {
        properties.setCompletionBonusAmount(0);
        ReferralServiceImpl service = service();
        when(referralCodeRepository.findByCustomerId(2L))
                .thenReturn(Optional.of(row(2L, "BKREFME000", 9L)));

        assertThat(service.completeReferral(2L, 55L).completed()).isFalse();
        verify(rewardLedgerRepository, never())
                .insertRewardIfAbsent(anyLong(), anyLong(), anyString(), anyDouble(),
                        anyString(), anyString(), any());
    }
}
