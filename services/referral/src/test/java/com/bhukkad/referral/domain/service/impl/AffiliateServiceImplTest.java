package com.bhukkad.referral.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.referral.api.dto.request.AffiliateCodeRequest;
import com.bhukkad.referral.api.dto.response.AffiliateCodeResponse;
import com.bhukkad.referral.api.dto.response.AffiliateStatsResponse;
import com.bhukkad.referral.domain.entity.AffiliateCode;
import com.bhukkad.referral.domain.entity.AffiliateReferral;
import com.bhukkad.referral.domain.entity.AffiliateReferral.AffiliateReferralStatus;
import com.bhukkad.referral.domain.repository.AffiliateCodeRepository;
import com.bhukkad.referral.domain.repository.AffiliateReferralRepository;
import com.bhukkad.referral.domain.service.impl.AffiliateServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AffiliateServiceImplTest {

    @Mock private AffiliateCodeRepository affiliateCodeRepository;
    @Mock private AffiliateReferralRepository affiliateReferralRepository;

    private AffiliateServiceImpl service() {
        return new AffiliateServiceImpl(affiliateCodeRepository, affiliateReferralRepository);
    }

    private static AffiliateCode existing(Long id, String code, boolean active) {
        AffiliateCode a = new AffiliateCode();
        a.setId(id);
        a.setCode(code);
        a.setName("Influencer");
        a.setChannel("youtube");
        a.setRewardAmount(50.0);
        a.setIsActive(active);
        a.setCreatedAt(LocalDateTime.now());
        return a;
    }

    private static AffiliateCodeRequest request(String code, String name, Double reward, Boolean active) {
        AffiliateCodeRequest r = new AffiliateCodeRequest();
        r.setCode(code);
        r.setName(name);
        r.setChannel("instagram");
        r.setRewardAmount(reward);
        r.setIsActive(active);
        return r;
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Test
    void create_valid_persistsNormalizedCodeWithDefaultActive() {
        when(affiliateCodeRepository.existsByCodeIgnoreCase("TOP-CHEF")).thenReturn(false);
        when(affiliateCodeRepository.save(any(AffiliateCode.class))).thenAnswer(inv -> {
            AffiliateCode a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });

        AffiliateCodeResponse response = service().create(request(" top-chef ", "  Raj ", 100.0, null));

        assertThat(response.getCode()).isEqualTo("TOP-CHEF");
        assertThat(response.getName()).isEqualTo("Raj");
        assertThat(response.getIsActive()).isTrue();
        assertThat(response.getRewardAmount()).isEqualTo(100.0);
        assertThat(response.getCreatedAt()).isNotNull();
    }

    @Test
    void create_duplicateCode_throws() {
        when(affiliateCodeRepository.existsByCodeIgnoreCase("TOP-CHEF")).thenReturn(true);

        assertThatThrownBy(() -> service().create(request("top-chef", "Raj", 10.0, true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void create_negativeOrNullReward_throws() {
        AffiliateServiceImpl service = service();
        when(affiliateCodeRepository.existsByCodeIgnoreCase(anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.create(request("A1", "Raj", null, true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("zero or positive");
        assertThatThrownBy(() -> service.create(request("A2", "Raj", -1.0, true)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void create_explicitInactive_keepsFalse() {
        when(affiliateCodeRepository.existsByCodeIgnoreCase("OFF")).thenReturn(false);
        when(affiliateCodeRepository.save(any(AffiliateCode.class))).thenAnswer(inv -> inv.getArgument(0));

        AffiliateCodeResponse response = service().create(request("off", "Raj", 0.0, false));

        assertThat(response.getIsActive()).isFalse();
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------

    @Test
    void update_missing_throwsNotFound() {
        when(affiliateCodeRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().update(9L, request("X", "Y", 1.0, true)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_codeTakenByOther_throws() {
        when(affiliateCodeRepository.findById(1L))
                .thenReturn(Optional.of(existing(1L, "MINE", true)));
        when(affiliateCodeRepository.existsByCodeIgnoreCase("THEIRS")).thenReturn(true);

        assertThatThrownBy(() -> service().update(1L, request("theirs", null, null, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void update_sameCodeCaseInsensitive_allowedAndNormalized() {
        when(affiliateCodeRepository.findById(1L))
                .thenReturn(Optional.of(existing(1L, "MINE", true)));
        when(affiliateCodeRepository.save(any(AffiliateCode.class))).thenAnswer(inv -> inv.getArgument(0));

        AffiliateCodeResponse response = service().update(1L, request(" mine ", null, null, null));

        assertThat(response.getCode()).isEqualTo("MINE");
    }

    @Test
    void update_partialFields_onlyAppliesProvided() {
        when(affiliateCodeRepository.findById(1L))
                .thenReturn(Optional.of(existing(1L, "MINE", true)));
        when(affiliateCodeRepository.save(any(AffiliateCode.class))).thenAnswer(inv -> inv.getArgument(0));

        AffiliateCodeResponse response = service().update(1L, request(null, " New Name ", null, false));

        assertThat(response.getName()).isEqualTo("New Name");
        assertThat(response.getIsActive()).isFalse();
        assertThat(response.getCode()).isEqualTo("MINE");
        assertThat(response.getChannel()).isEqualTo("instagram");
    }

    @Test
    void update_negativeReward_throws() {
        when(affiliateCodeRepository.findById(1L))
                .thenReturn(Optional.of(existing(1L, "MINE", true)));

        assertThatThrownBy(() -> service().update(1L, request(null, null, -5.0, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("zero or positive");
    }

    @Test
    void update_channelAndRewardProvided_applied() {
        when(affiliateCodeRepository.findById(1L))
                .thenReturn(Optional.of(existing(1L, "MINE", true)));
        when(affiliateCodeRepository.save(any(AffiliateCode.class))).thenAnswer(inv -> inv.getArgument(0));

        AffiliateCodeResponse response = service().update(1L, request(null, null, 25.0, true));

        assertThat(response.getRewardAmount()).isEqualTo(25.0);
        assertThat(response.getIsActive()).isTrue();
    }

    // ------------------------------------------------------------------
    // deactivate
    // ------------------------------------------------------------------

    @Test
    void deactivate_found_flipsFlag() {
        AffiliateCode code = existing(1L, "MINE", true);
        when(affiliateCodeRepository.findById(1L)).thenReturn(Optional.of(code));

        service().deactivate(1L);

        assertThat(code.getIsActive()).isFalse();
        verify(affiliateCodeRepository).save(code);
    }

    @Test
    void deactivate_missing_throwsNotFound() {
        when(affiliateCodeRepository.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().deactivate(2L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // recordSignup
    // ------------------------------------------------------------------

    @Test
    void recordSignup_blankCodeOrNullCustomer_noop() {
        AffiliateServiceImpl service = service();

        service.recordSignup(" ", 1L, "a@b.c");
        service.recordSignup("CODE", null, "a@b.c");

        verify(affiliateCodeRepository, never()).findByCodeIgnoreCase(anyString());
    }

    @Test
    void recordSignup_unknownCode_throws() {
        when(affiliateCodeRepository.findByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().recordSignup("nope", 1L, "a@b.c"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid affiliate code");
    }

    @Test
    void recordSignup_inactiveCode_throws() {
        when(affiliateCodeRepository.findByCodeIgnoreCase("GONE"))
                .thenReturn(Optional.of(existing(1L, "GONE", false)));

        assertThatThrownBy(() -> service().recordSignup("gone", 1L, "a@b.c"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void recordSignup_alreadyTracked_noop() {
        AffiliateCode code = existing(1L, "LIVE", true);
        when(affiliateCodeRepository.findByCodeIgnoreCase("LIVE")).thenReturn(Optional.of(code));
        when(affiliateReferralRepository.existsByAffiliateCodeIdAndCustomerId(1L, 8L)).thenReturn(true);

        service().recordSignup("live", 8L, "a@b.c");

        verify(affiliateReferralRepository, never()).save(any());
    }

    @Test
    void recordSignup_new_persistsPendingReferralWithSnapshot() {
        AffiliateCode code = existing(1L, "LIVE", true);
        when(affiliateCodeRepository.findByCodeIgnoreCase("LIVE")).thenReturn(Optional.of(code));
        when(affiliateReferralRepository.existsByAffiliateCodeIdAndCustomerId(1L, 8L)).thenReturn(false);

        service().recordSignup("live", 8L, "a@b.c");

        ArgumentCaptor<AffiliateReferral> saved = ArgumentCaptor.forClass(AffiliateReferral.class);
        verify(affiliateReferralRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AffiliateReferralStatus.PENDING);
        assertThat(saved.getValue().getCustomerId()).isEqualTo(8L);
        assertThat(saved.getValue().getCustomerEmail()).isEqualTo("a@b.c");
        assertThat(saved.getValue().getRewardAmount()).isEqualTo(50.0);
        assertThat(saved.getValue().getAffiliateCode()).isSameAs(code);
        assertThat(saved.getValue().getCreatedAt()).isNotNull();
    }

    // ------------------------------------------------------------------
    // getStats
    // ------------------------------------------------------------------

    @Test
    void getStats_missing_throwsNotFound() {
        when(affiliateCodeRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getStats(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getStats_aggregatesCountsAndRecentEntries() {
        AffiliateCode code = existing(1L, "TOP", true);
        when(affiliateCodeRepository.findById(1L)).thenReturn(Optional.of(code));
        when(affiliateReferralRepository.countByAffiliateCodeId(1L)).thenReturn(12L);
        when(affiliateReferralRepository.countByAffiliateCodeIdAndStatus(1L, AffiliateReferralStatus.PAID))
                .thenReturn(4L);
        when(affiliateReferralRepository.sumRewardByAffiliateCodeId(1L)).thenReturn(600.0);

        AffiliateReferral referral = new AffiliateReferral();
        referral.setId(77L);
        referral.setCustomerId(8L);
        referral.setCustomerEmail("a@b.c");
        referral.setRewardAmount(50.0);
        referral.setStatus(AffiliateReferralStatus.PAID);
        referral.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4));
        when(affiliateReferralRepository.findTop20ByAffiliateCodeIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(referral));

        AffiliateStatsResponse stats = service().getStats(1L);

        assertThat(stats.code()).isEqualTo("TOP");
        assertThat(stats.affiliateCodeId()).isEqualTo(1L);
        assertThat(stats.name()).isEqualTo("Influencer");
        assertThat(stats.totalReferrals()).isEqualTo(12);
        assertThat(stats.paidReferrals()).isEqualTo(4);
        assertThat(stats.totalReward()).isEqualTo(600.0);
        assertThat(stats.recentReferrals()).hasSize(1);
        assertThat(stats.recentReferrals().get(0).status()).isEqualTo("PAID");
        assertThat(stats.recentReferrals().get(0).id()).isEqualTo(77L);
        assertThat(stats.recentReferrals().get(0).createdAt()).isEqualTo("2026-01-02T03:04");
    }
}
