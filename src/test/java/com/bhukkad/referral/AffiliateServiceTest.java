package com.bhukkad.referral;

import com.bhukkad.dto.request.AffiliateCodeRequest;
import com.bhukkad.entity.AffiliateCode;
import com.bhukkad.entity.AffiliateReferral;
import com.bhukkad.entity.Customer;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.AffiliateCodeRepository;
import com.bhukkad.repository.AffiliateReferralRepository;
import com.bhukkad.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AffiliateServiceTest {

    @Mock
    private AffiliateCodeRepository affiliateCodeRepository;
    @Mock
    private AffiliateReferralRepository affiliateReferralRepository;
    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private AffiliateService service;

    private AffiliateCodeRequest validRequest() {
        AffiliateCodeRequest request = new AffiliateCodeRequest();
        request.setCode("foodie-vivek");
        request.setName("Vivek");
        request.setChannel("INSTAGRAM");
        request.setRewardAmount(40.0);
        return request;
    }

    @Test
    void create_normalizesCodeAndSaves() {
        when(affiliateCodeRepository.existsByCodeIgnoreCase("FOODIE-VIVEK")).thenReturn(false);
        when(affiliateCodeRepository.save(any(AffiliateCode.class))).thenAnswer(inv -> inv.getArgument(0));

        AffiliateCodeRequest request = validRequest();
        request.setCode(" foodie-vivek ");

        var response = service.create(request);

        assertEquals("FOODIE-VIVEK", response.getCode());
        assertEquals(40.0, response.getRewardAmount());
        assertTrue(response.getIsActive());
    }

    @Test
    void create_duplicateCode_throwsBusinessException() {
        when(affiliateCodeRepository.existsByCodeIgnoreCase("FOODIE-VIVEK")).thenReturn(true);

        assertThrows(BusinessException.class, () -> service.create(validRequest()));
        verify(affiliateCodeRepository, never()).save(any());
    }

    @Test
    void create_negativeReward_throwsBusinessException() {
        when(affiliateCodeRepository.existsByCodeIgnoreCase("FOODIE-VIVEK")).thenReturn(false);

        AffiliateCodeRequest request = validRequest();
        request.setRewardAmount(-5.0);

        assertThrows(BusinessException.class, () -> service.create(request));
    }

    @Test
    void recordSignup_validCode_tracksReferral() {
        AffiliateCode code = new AffiliateCode();
        code.setId(1L);
        code.setCode("FOODIE-VIVEK");
        code.setIsActive(true);
        code.setRewardAmount(40.0);

        Customer customer = new Customer();
        customer.setId(7L);
        customer.setEmail("new@bhukkad.test");

        when(affiliateCodeRepository.findByCodeIgnoreCase("FOODIE-VIVEK")).thenReturn(Optional.of(code));
        when(affiliateReferralRepository.existsByAffiliateCodeIdAndCustomerId(1L, 7L)).thenReturn(false);
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer));

        service.recordSignup("foodie-vivek", 7L);

        verify(affiliateReferralRepository).save(any(AffiliateReferral.class));
    }

    @Test
    void recordSignup_duplicateSignup_isIdempotent() {
        AffiliateCode code = new AffiliateCode();
        code.setId(1L);
        code.setCode("FOODIE-VIVEK");
        code.setIsActive(true);

        when(affiliateCodeRepository.findByCodeIgnoreCase("FOODIE-VIVEK")).thenReturn(Optional.of(code));
        when(affiliateReferralRepository.existsByAffiliateCodeIdAndCustomerId(1L, 7L)).thenReturn(true);

        service.recordSignup("foodie-vivek", 7L);

        verify(affiliateReferralRepository, never()).save(any());
    }

    @Test
    void recordSignup_unknownCode_throwsBusinessException() {
        when(affiliateCodeRepository.findByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.recordSignup("nope", 7L));
    }

    @Test
    void recordSignup_inactiveCode_throwsBusinessException() {
        AffiliateCode code = new AffiliateCode();
        code.setId(1L);
        code.setCode("FOODIE-VIVEK");
        code.setIsActive(false);

        when(affiliateCodeRepository.findByCodeIgnoreCase("FOODIE-VIVEK")).thenReturn(Optional.of(code));

        assertThrows(BusinessException.class, () -> service.recordSignup("foodie-vivek", 7L));
    }

    @Test
    void recordSignup_blankCode_isNoOp() {
        service.recordSignup("", 7L);
        service.recordSignup(null, 7L);
        verify(affiliateCodeRepository, never()).findByCodeIgnoreCase(anyString());
    }

    @Test
    void getStats_aggregatesReferrals() {
        AffiliateCode code = new AffiliateCode();
        code.setId(1L);
        code.setCode("FOODIE-VIVEK");
        code.setName("Vivek");

        Customer customer = new Customer();
        customer.setId(7L);
        customer.setEmail("new@bhukkad.test");

        AffiliateReferral referral = new AffiliateReferral();
        referral.setId(10L);
        referral.setAffiliateCode(code);
        referral.setCustomer(customer);
        referral.setRewardAmount(40.0);
        referral.setStatus(AffiliateReferral.AffiliateReferralStatus.PENDING);
        referral.setCreatedAt(java.time.LocalDateTime.now());

        when(affiliateCodeRepository.findById(1L)).thenReturn(Optional.of(code));
        when(affiliateReferralRepository.countByAffiliateCodeId(1L)).thenReturn(5L);
        when(affiliateReferralRepository.countByAffiliateCodeIdAndStatus(
                1L, AffiliateReferral.AffiliateReferralStatus.PAID)).thenReturn(2L);
        when(affiliateReferralRepository.sumRewardByAffiliateCodeId(1L)).thenReturn(200.0);
        when(affiliateReferralRepository.findByAffiliateCodeIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(referral));

        var stats = service.getStats(1L);

        assertEquals(5, stats.getTotalReferrals());
        assertEquals(2, stats.getPaidReferrals());
        assertEquals(200.0, stats.getTotalReward());
        assertEquals(1, stats.getRecentReferrals().size());
        assertEquals(7L, stats.getRecentReferrals().get(0).getCustomerId());
    }

    @Test
    void getStats_unknownCode_throwsNotFound() {
        when(affiliateCodeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getStats(99L));
    }

    @Test
    void deactivate_marksInactive() {
        AffiliateCode code = new AffiliateCode();
        code.setId(1L);
        code.setCode("FOODIE-VIVEK");
        code.setIsActive(true);

        when(affiliateCodeRepository.findById(1L)).thenReturn(Optional.of(code));
        when(affiliateCodeRepository.save(any(AffiliateCode.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivate(1L);

        assertFalse(code.getIsActive());
    }
}
