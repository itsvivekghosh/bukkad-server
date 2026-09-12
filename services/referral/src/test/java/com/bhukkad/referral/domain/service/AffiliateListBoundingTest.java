package com.bhukkad.referral.domain.service;

import com.bhukkad.referral.domain.entity.AffiliateCode;
import com.bhukkad.referral.domain.repository.AffiliateCodeRepository;
import com.bhukkad.referral.domain.repository.AffiliateReferralRepository;
import com.bhukkad.referral.domain.service.impl.AffiliateServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PERF-3 §2.1: the affiliate registry endpoint previously listed the whole
 * table into memory. Now a bounded newest-first SQL page (shape kept; cap is
 * documented). Both owners of the concept (referral + identity copies) are
 * bounded here — the single-owner ADR (R-04) is a separate track.
 */
@ExtendWith(MockitoExtension.class)
class AffiliateListBoundingTest {

    @Mock private AffiliateCodeRepository affiliateCodeRepository;
    @Mock private AffiliateReferralRepository affiliateReferralRepository;

    @Test
    void listAll_usesBoundedPage_neverFindAll() {
        AffiliateCode code = new AffiliateCode();
        code.setId(1L);
        code.setCode("TOP-CHEF");
        when(affiliateCodeRepository.findAllByOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.any(Pageable.class))).thenReturn(List.of(code));

        AffiliateServiceImpl service =
                new AffiliateServiceImpl(affiliateCodeRepository, affiliateReferralRepository);
        var result = service.listAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCode()).isEqualTo("TOP-CHEF");

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(affiliateCodeRepository).findAllByOrderByCreatedAtDesc(page.capture());
        // ORDER BY created_at DESC is expressed by the derived method name;
        // the pageable carries the 200-row cap.
        assertThat(page.getValue().getPageSize()).isEqualTo(200);
        verify(affiliateCodeRepository, never()).findAll();
    }
}
