package com.bhukkad.wallet;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.WalletTransactionRepository;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final CustomerRepository customerRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    @Transactional
    public void credit(Customer customer, double amount, WalletTransaction.TransactionType type,
                       Payment payment, String description) {
        if (amount <= 0) {
            throw new BusinessException("Credit amount must be positive");
        }
        double newBalance = PriceCalculator.roundToTwoDecimals(customer.getWalletBalance() + amount);
        customer.setWalletBalance(newBalance);
        customerRepository.save(customer);
        recordTransaction(customer, payment, type, amount, newBalance, description);
    }

    @Transactional
    public void debit(Customer customer, double amount, WalletTransaction.TransactionType type,
                      Payment payment, String description) {
        if (amount <= 0) {
            throw new BusinessException("Debit amount must be positive");
        }
        if (customer.getWalletBalance() < amount) {
            throw new BusinessException("Insufficient wallet balance");
        }
        double newBalance = PriceCalculator.roundToTwoDecimals(customer.getWalletBalance() - amount);
        customer.setWalletBalance(newBalance);
        customerRepository.save(customer);
        recordTransaction(customer, payment, type, -amount, newBalance, description);
    }

    private void recordTransaction(Customer customer, Payment payment, WalletTransaction.TransactionType type,
                                   double signedAmount, double balanceAfter, String description) {
        WalletTransaction tx = new WalletTransaction();
        tx.setCustomer(customer);
        tx.setPayment(payment);
        tx.setType(type);
        tx.setAmount(PriceCalculator.roundToTwoDecimals(Math.abs(signedAmount)));
        tx.setBalanceAfter(balanceAfter);
        tx.setDescription(description);
        walletTransactionRepository.save(tx);
    }
}
