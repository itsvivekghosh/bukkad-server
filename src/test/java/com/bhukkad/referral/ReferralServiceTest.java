package com.bhukkad.referral;

import com.bhukkad.config.ReferralProperties;
import com.bhukkad.dto.response.ReferralInfoResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.WalletTransactionRepository;
import com.bhukkad.wallet.WalletService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferralServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private WalletService walletService;
    @Mock
    private WalletTransactionRepository walletTransactionRepository;
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
        verify(customerRepository).save(customer);
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

        assertEquals(referrer, customer.getReferredBy());
        verify(walletService, org.mockito.Mockito.times(2)).credit(any(Customer.class), any(Double.class),
                any(WalletTransaction.TransactionType.class), any(), anyString());
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
        when(walletTransactionRepository.sumReferralCredits(1L, WalletTransaction.TransactionType.REFERRAL_BONUS))
                .thenReturn(150.0);

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
}