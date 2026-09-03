package com.bhukkad.identity.referral;

import com.bhukkad.common.error.BusinessException;
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

@ExtendWith(MockitoExtension.class)
class ReferralServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private WalletCreditPort walletCreditPort;
    @Mock
    private ReferralProperties referralProperties;

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
    void initializeNewCustomer_assignsCodeAndSaves() {
        when(customerRepository.findByReferralCode(anyString())).thenReturn(Optional.empty());
        when(referralProperties.isEnabled()).thenReturn(false);

        service.initializeNewCustomer(customer, null);

        assertNotNull(customer.getReferralCode());
        // The method no longer saves the customer itself: the caller persists
        // the (managed) entity inside its own transaction to avoid a redundant
        // second round-trip.
        verify(customerRepository, never()).save(customer);
    }

    @Test
    void initializeNewCustomer_appliesReferral_whenEnabledAndCodeProvided() {
        // First findByReferralCode call is the generated-code uniqueness check,
        // second is the supplied referral code lookup in applyReferral.
        when(customerRepository.findByReferralCode(anyString()))
                .thenReturn(Optional.empty(), Optional.of(referrer));
        when(referralProperties.isEnabled()).thenReturn(true);
        when(referralProperties.getBonusAmount()).thenReturn(50.0);
        when(referralProperties.getRefereeBonusAmount()).thenReturn(25.0);

        service.initializeNewCustomer(customer, "bkabc123");

        assertEquals(referrer.getId(), customer.getReferredById());
        verify(walletCreditPort, times(2)).credit(any(Long.class), any(Double.class),
                eq("REFERRAL_BONUS"), isNull(), anyString());
    }

    @Test
    void initializeNewCustomer_skipsWalletCredit_whenBonusZero() {
        when(customerRepository.findByReferralCode(anyString()))
                .thenReturn(Optional.empty(), Optional.of(referrer));
        when(referralProperties.isEnabled()).thenReturn(true);
        when(referralProperties.getBonusAmount()).thenReturn(0.0);
        when(referralProperties.getRefereeBonusAmount()).thenReturn(0.0);

        service.initializeNewCustomer(customer, "BKABC123");

        verify(walletCreditPort, never()).credit(any(), anyDouble(), any(), any(), any());
    }

    @Test
    void initializeNewCustomer_throws_whenInvalidReferralCode() {
        when(customerRepository.findByReferralCode(anyString())).thenReturn(Optional.empty());
        when(referralProperties.isEnabled()).thenReturn(true);

        assertThrows(BusinessException.class,
                () -> service.initializeNewCustomer(customer, "UNKNOWN"));
    }

    @Test
    void initializeNewCustomer_rejectsOwnReferralCode() {
        customer.setId(2L);
        customer.setReferralCode("BKABC123");
        when(customerRepository.findByReferralCode(anyString()))
                .thenReturn(Optional.empty(), Optional.of(customer));
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
    void generateAndSaveReferralCode_generatesWhenAbsent() {
        customer.setReferralCode(null);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(customerRepository.findByReferralCode(anyString())).thenReturn(Optional.empty());

        String code = service.generateAndSaveReferralCode(1L);

        assertNotNull(code);
        verify(customerRepository).save(customer);
    }

    @Test
    void generateAndSaveReferralCode_returnsExistingWithoutSave() {
        customer.setReferralCode("BKEXIST1");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));

        String code = service.generateAndSaveReferralCode(1L);

        assertEquals("BKEXIST1", code);
        verify(customerRepository, never()).save(any());
    }

    @Test
    void isValidReferralCode_falseForBlankOrUnknown() {
        assertEquals(false, service.isValidReferralCode(null));
        assertEquals(false, service.isValidReferralCode(""));
        assertEquals(false, service.isValidReferralCode("NOPE"));
    }

    @Test
    void assertNotRateLimited_passesWhenAllowed() {
        when(customerRepository.findByReferralCode("BKABC123")).thenReturn(Optional.of(referrer));

        assertEquals(true, service.isValidReferralCode("BKABC123"));
    }
}
