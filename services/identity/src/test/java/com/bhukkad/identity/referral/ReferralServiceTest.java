package com.bhukkad.identity.referral;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.ratelimit.RateLimitDecision;
import com.bhukkad.common.ratelimit.RateLimitExceededException;
import com.bhukkad.common.ratelimit.RateLimitService;
import com.bhukkad.identity.api.WalletCreditPort;
import com.bhukkad.identity.domain.Customer;
import com.bhukkad.identity.domain.CustomerRepository;
import com.bhukkad.identity.dto.response.ReferralInfoResponse;
import com.bhukkad.identity.service.ReferralProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-005 (audit feature #4): identity is no longer a code generator — the
 * "BK + id + modulo tail" generation is deleted and delegated to the referral
 * module's internal API via {@link ReferralServiceClient} (service-JWT).
 * Identity's public contract is unchanged: the customer row still carries the
 * referral code (display mirror) and referredById, and the referee welcome
 * bonus still flows through {@link WalletCreditPort} exactly once per binding.
 */
@ExtendWith(MockitoExtension.class)
class ReferralServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private WalletCreditPort walletCreditPort;
    @Mock
    private ReferralProperties referralProperties;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private ReferralServiceClient referralServiceClient;

    @InjectMocks
    private ReferralService service;

    private Customer customer;
    private Customer referrer;

    @BeforeEach
    void setUp() {
        customer = new Customer();
        customer.setId(1L);
        customer.setEmail("new@example.com");

        referrer = new Customer();
        referrer.setId(2L);
        referrer.setReferralCode("BKABC123");
    }

    @Test
    void initializeNewCustomer_delegatesCodeGenerationToReferralModule() {
        when(referralServiceClient.generateCode(1L)).thenReturn("BKDELEGATE01");
        when(referralProperties.isEnabled()).thenReturn(false);

        service.initializeNewCustomer(customer, null);

        assertEquals("BKDELEGATE01", customer.getReferralCode());
        // ADR-005: no local "BK + id + random" generation and no second code.
        verify(referralServiceClient).generateCode(1L);
        verify(customerRepository, never()).save(customer);
    }

    @Test
    void initializeNewCustomer_fallsBackToCollisionSafeLocalCode_whenReferralUnreachable() {
        when(referralServiceClient.generateCode(1L)).thenReturn(null);
        when(referralProperties.isEnabled()).thenReturn(false);

        service.initializeNewCustomer(customer, null);

        assertNotNull(customer.getReferralCode());
        assertTrue(customer.getReferralCode().matches("BK1[A-Z0-9]{8}"));
    }

    @Test
    void initializeNewCustomer_appliesReferralViaClient_creditsRefereeBonusOnly() {
        when(customerRepository.findByReferralCode("BKABC123")).thenReturn(Optional.of(referrer));
        when(referralServiceClient.generateCode(1L)).thenReturn("BKDELEGATE01");
        when(referralProperties.isEnabled()).thenReturn(true);
        when(referralProperties.getRefereeBonusAmount()).thenReturn(25.0);
        when(referralServiceClient.applyReferral(1L, "new@example.com", "BKABC123")).thenReturn(true);

        service.initializeNewCustomer(customer, "bkabc123");

        assertEquals(referrer.getId(), customer.getReferredById());
        // The referee welcome bonus stays identity-owned; the REFERRER reward
        // is now exactly-once ledger bookkeeping owned by the referral module.
        verify(walletCreditPort, times(1)).credit(eq(1L), eq(25.0),
                eq("REFERRAL_BONUS"), isNull(), anyString());
        verify(walletCreditPort, never()).credit(eq(2L), anyDouble(), any(), any(), any());
        verify(referralServiceClient).applyReferral(1L, "new@example.com", "BKABC123");
    }

    @Test
    void initializeNewCustomer_skipsWalletCredit_whenClientRejects() {
        when(customerRepository.findByReferralCode("BKABC123")).thenReturn(Optional.of(referrer));
        when(referralServiceClient.generateCode(1L)).thenReturn("BKDELEGATE01");
        when(referralProperties.isEnabled()).thenReturn(true);
        when(referralServiceClient.applyReferral(anyLong(), anyString(), anyString())).thenReturn(false);

        service.initializeNewCustomer(customer, "BKABC123");

        verify(walletCreditPort, never()).credit(any(), anyDouble(), any(), any(), any());
    }

    @Test
    void initializeNewCustomer_noWalletCredit_whenBonusZero() {
        when(customerRepository.findByReferralCode("BKABC123")).thenReturn(Optional.of(referrer));
        when(referralServiceClient.generateCode(1L)).thenReturn("BKDELEGATE01");
        when(referralProperties.isEnabled()).thenReturn(true);
        when(referralServiceClient.applyReferral(anyLong(), anyString(), anyString())).thenReturn(true);
        when(referralProperties.getRefereeBonusAmount()).thenReturn(0.0);

        service.initializeNewCustomer(customer, "BKABC123");

        assertEquals(referrer.getId(), customer.getReferredById());
        verify(walletCreditPort, never()).credit(any(), anyDouble(), any(), any(), any());
    }

    @Test
    void initializeNewCustomer_rejectsAlreadyReferredCustomer_withoutRebinding() {
        customer.setReferredById(9L);
        when(referralServiceClient.generateCode(1L)).thenReturn("BKDELEGATE01");
        when(customerRepository.findByReferralCode("BKABC123")).thenReturn(Optional.of(referrer));
        when(referralProperties.isEnabled()).thenReturn(true);

        service.initializeNewCustomer(customer, "BKABC123");

        // Early-return guard (ADR-005): binding untouched, no client call.
        assertEquals(9L, customer.getReferredById());
        verify(referralServiceClient, never()).applyReferral(any(), any(), any());
    }

    @Test
    void initializeNewCustomer_throws_whenInvalidReferralCode() {
        when(customerRepository.findByReferralCode("UNKNOWN")).thenReturn(Optional.empty());
        when(referralServiceClient.generateCode(1L)).thenReturn("BKDELEGATE01");
        when(referralProperties.isEnabled()).thenReturn(true);

        assertThrows(BusinessException.class,
                () -> service.initializeNewCustomer(customer, "UNKNOWN"));
        verify(referralServiceClient, never()).applyReferral(any(), any(), any());
    }

    @Test
    void initializeNewCustomer_rejectsOwnReferralCode() {
        customer.setId(2L);
        customer.setReferralCode("BKABC123");
        when(customerRepository.findByReferralCode("BKABC123")).thenReturn(Optional.of(customer));
        when(referralServiceClient.generateCode(2L)).thenReturn("BKDELEGATE01");
        when(referralProperties.isEnabled()).thenReturn(true);

        assertThrows(BusinessException.class,
                () -> service.initializeNewCustomer(customer, "BKABC123"));
    }

    @Test
    void getReferralInfo_returnsInfo() {
        customer.setReferralCode("BKXYZ99");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(customerRepository.countByReferredById(1L)).thenReturn(3L);
        when(walletCreditPort.referralBonusEarned(1L)).thenReturn(150.0);

        ReferralInfoResponse result = service.getReferralInfo(1L);

        assertEquals("BKXYZ99", result.getReferralCode());
        assertEquals(3, result.getReferralsCount());
        assertEquals(150.0, result.getReferralBonusEarned());
    }

    @Test
    void getReferralInfo_throws_whenCustomerNotFound() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.getReferralInfo(99L));
    }

    @Test
    void getReferralInfo_throws_whenNoCodeAssigned() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        assertThrows(BusinessException.class, () -> service.getReferralInfo(1L));
    }

    @Test
    void generateAndSaveReferralCode_delegatesAndPersistsMirror() {
        customer.setReferralCode(null);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(referralServiceClient.generateCode(1L)).thenReturn("BKDELEGATE01");

        String code = service.generateAndSaveReferralCode(1L);

        assertEquals("BKDELEGATE01", code);
        verify(customerRepository).save(customer);
    }

    @Test
    void generateAndSaveReferralCode_returnsExistingWithoutSave() {
        customer.setReferralCode("BKEXIST1");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));

        String code = service.generateAndSaveReferralCode(1L);

        assertEquals("BKEXIST1", code);
        verify(customerRepository, never()).save(any());
        verify(referralServiceClient, never()).generateCode(any());
    }

    @Test
    void isValidReferralCode_falseForBlankOrUnknown() {
        when(customerRepository.findByReferralCode("NOPE")).thenReturn(Optional.empty());

        assertEquals(false, service.isValidReferralCode(null));
        assertEquals(false, service.isValidReferralCode(""));
        assertEquals(false, service.isValidReferralCode("NOPE"));
    }

    @Test
    void isValidReferralCode_trueWhenPresent() {
        when(customerRepository.findByReferralCode("BKABC123")).thenReturn(Optional.of(referrer));

        assertEquals(true, service.isValidReferralCode("BKABC123"));
    }

    @Test
    void assertNotRateLimited_passesWhenAllowed() {
        when(rateLimitService.check("referral", "referral:generate:1", 10L, 60))
                .thenReturn(RateLimitDecision.allowed(1L, 10L, 60L));

        service.assertNotRateLimited("referral:generate:1");
    }

    @Test
    void assertNotRateLimited_throwsWhenDenied() {
        when(rateLimitService.check("referral", "referral:generate:1", 10L, 60))
                .thenReturn(RateLimitDecision.denied(11L, 10L, 42L));

        RateLimitExceededException ex = assertThrows(RateLimitExceededException.class,
                () -> service.assertNotRateLimited("referral:generate:1"));

        assertEquals(42L, ex.getRetryAfterSeconds());
    }
}
