package com.bhukkad.wallet;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private WalletTransactionRepository walletTransactionRepository;

    @InjectMocks
    private WalletService service;

    private Customer customer;

    @BeforeEach
    void setUp() {
        customer = new Customer();
        customer.setId(1L);
        customer.setWalletBalance(100.0);
        // findByIdWithLock returns the same entity (identity map); the lock is a
        // DB-level serialisation guard, not a different instance.
        lenient().when(customerRepository.findByIdWithLock(1L)).thenReturn(java.util.Optional.of(customer));
    }

    @Test
    void credit_addsToBalanceAndRecordsTransaction() {
        service.credit(customer, 50.0, WalletTransaction.TransactionType.REFERRAL_BONUS, null, "bonus");

        assertEquals(150.0, customer.getWalletBalance());
        verify(customerRepository).save(customer);
        verify(walletTransactionRepository).save(any(WalletTransaction.class));
    }

    @Test
    void credit_throws_whenAmountNotPositive() {
        assertThrows(BusinessException.class,
                () -> service.credit(customer, 0, WalletTransaction.TransactionType.REFERRAL_BONUS, null, null));
        assertThrows(BusinessException.class,
                () -> service.credit(customer, -10, WalletTransaction.TransactionType.REFERRAL_BONUS, null, null));
        verifyNoInteractions(walletTransactionRepository);
    }

    @Test
    void debit_subtractsFromBalance() {
        service.debit(customer, 40.0, WalletTransaction.TransactionType.ORDER_DEBIT, mockPayment(), "order");

        assertEquals(60.0, customer.getWalletBalance());
        verify(customerRepository).save(customer);
        verify(walletTransactionRepository).save(any(WalletTransaction.class));
    }

    @Test
    void debit_throws_whenInsufficientBalance() {
        assertThrows(BusinessException.class,
                () -> service.debit(customer, 500.0, WalletTransaction.TransactionType.ORDER_DEBIT, null, "order"));
        assertEquals(100.0, customer.getWalletBalance());
    }

    @Test
    void debit_throws_whenAmountNotPositive() {
        assertThrows(BusinessException.class,
                () -> service.debit(customer, 0, WalletTransaction.TransactionType.ORDER_DEBIT, null, null));
    }

    private Payment mockPayment() {
        return new Payment();
    }
}