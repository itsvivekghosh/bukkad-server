package com.bhukkad.commerce.payment.domain.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.commerce.payment.domain.entity.WalletBalance;
import com.bhukkad.commerce.payment.domain.entity.WalletTransaction;
import com.bhukkad.commerce.payment.domain.repository.WalletBalanceRepository;
import com.bhukkad.commerce.payment.domain.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private static final int MAX_OPTIMISTIC_RETRIES = 3;

    private final WalletBalanceRepository balanceRepository;
    private final WalletTransactionRepository transactionRepository;

    @Transactional
    public WalletBalance credit(Long customerId, BigDecimal amount, String reference) {
        if (amount.signum() <= 0) throw new BusinessException("Credit amount must be positive");
        return applyDelta(customerId, amount, BigDecimal::add, "CREDIT", reference, true);
    }

    @Transactional
    public WalletBalance debit(Long customerId, BigDecimal amount, String reference) {
        if (amount.signum() <= 0) throw new BusinessException("Debit amount must be positive");
        return applyDelta(customerId, amount, BigDecimal::subtract, "DEBIT", reference, false);
    }

    private WalletBalance applyDelta(Long customerId, BigDecimal delta,
                                     java.util.function.BinaryOperator<BigDecimal> combiner,
                                     String type, String reference, boolean createIfMissing) {
        int attempt = 0;
        while (true) {
            try {
                WalletBalance wb = balanceRepository.findByCustomerIdForUpdate(customerId)
                        .orElseGet(() -> {
                            if (!createIfMissing) {
                                throw new ResourceNotFoundException(
                                        "Wallet not found for customer " + customerId);
                            }
                            WalletBalance b = new WalletBalance();
                            b.setCustomerId(customerId);
                            b.setBalance(BigDecimal.ZERO);
                            b.setUpdatedAt(LocalDateTime.now());
                            return balanceRepository.save(b);
                        });
                if ("DEBIT".equals(type) && wb.getBalance().compareTo(delta) < 0) {
                    throw new BusinessException("Insufficient wallet balance");
                }
                wb.setBalance(combiner.apply(wb.getBalance(), delta));
                wb.setUpdatedAt(LocalDateTime.now());
                WalletBalance saved = balanceRepository.save(wb);
                recordTx(customerId, type, delta.signum() < 0 ? delta.negate() : delta,
                        saved.getBalance(), reference);
                return saved;
            } catch (ObjectOptimisticLockingFailureException ex) {
                if (++attempt >= MAX_OPTIMISTIC_RETRIES) {
                    throw new BusinessException("Wallet update failed due to concurrent modification", ex);
                }
                // Retry with fresh read
            }
        }
    }

    @Transactional(readOnly = true)
    public WalletBalance balance(Long customerId) {
        return balanceRepository.findByCustomerId(customerId).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<WalletTransaction> transactions(Long customerId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), com.bhukkad.common.util.PaginationUtils.MAX_PAGE_SIZE);
        return transactionRepository.findByCustomerId(customerId,
                org.springframework.data.domain.PageRequest.of(safePage, safeSize)).getContent();
    }

    private void recordTx(Long customerId, String type, BigDecimal amount, BigDecimal balanceAfter, String reference) {
        WalletTransaction tx = new WalletTransaction();
        tx.setCustomerId(customerId);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setBalanceAfter(balanceAfter);
        tx.setReference(reference);
        transactionRepository.save(tx);
    }

    @Transactional(readOnly = true)
    public List<WalletTransaction> transactionsPage(Long customerId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), com.bhukkad.common.util.PaginationUtils.MAX_PAGE_SIZE);
        return transactionRepository.findByCustomerId(customerId,
                org.springframework.data.domain.PageRequest.of(safePage, safeSize)).getContent();
    }

    @Transactional(readOnly = true)
    public CursorPage transactionsByCursor(Long customerId, String cursor, int size) {
        com.bhukkad.common.util.CursorUtils.OrderCursor c =
                com.bhukkad.common.util.CursorUtils.decode(cursor).orElse(null);
        LocalDateTime cursorCreatedAt = c != null ? c.createdAt() : com.bhukkad.common.util.CursorUtils.END_OF_TIME;
        Long cursorId = c != null ? c.id() : com.bhukkad.common.util.CursorUtils.END_OF_ID;
        int safeSize = Math.min(Math.max(size, 1), com.bhukkad.common.util.PaginationUtils.MAX_PAGE_SIZE);
        Page<WalletTransaction> batch = transactionRepository.afterCursor(
                customerId, cursorCreatedAt, cursorId,
                org.springframework.data.domain.PageRequest.of(0, safeSize + 1));
        boolean hasNext = batch.getTotalElements() > safeSize;
        List<WalletTransaction> pageItems = hasNext ? batch.getContent().subList(0, safeSize) : batch.getContent();
        String nextCursor = null;
        if (hasNext && !pageItems.isEmpty()) {
            WalletTransaction last = pageItems.get(pageItems.size() - 1);
            nextCursor = com.bhukkad.common.util.CursorUtils.encode(last.getCreatedAt(), last.getId());
        }
        return new CursorPage(pageItems, nextCursor, hasNext);
    }

    public record CursorPage(List<WalletTransaction> items, String nextCursor, boolean hasNext) {
    }
}