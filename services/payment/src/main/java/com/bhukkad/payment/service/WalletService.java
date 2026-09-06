package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.payment.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Wallet management (port of monolith {@code WalletService}): credit/debit
 * balance, transaction history, and top-up. The wallet is the authoritative
 * customer balance (single-writer money path).
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletBalanceRepository balanceRepository;
    private final WalletTransactionRepository transactionRepository;

    @Transactional
    public WalletBalance credit(Long customerId, BigDecimal amount, String reference) {
        if (amount.signum() <= 0) throw new BusinessException("Credit amount must be positive");
        WalletBalance wb = balanceRepository.findByCustomerId(customerId)
                .orElseGet(() -> {
                    WalletBalance b = new WalletBalance();
                    b.setCustomerId(customerId);
                    b.setBalance(BigDecimal.ZERO);
                    b.setUpdatedAt(LocalDateTime.now());
                    return balanceRepository.save(b);
                });
        wb.setBalance(wb.getBalance().add(amount));
        wb.setUpdatedAt(LocalDateTime.now());
        balanceRepository.save(wb);

        recordTx(customerId, "CREDIT", amount, wb.getBalance(), reference);
        return wb;
    }

    @Transactional
    public WalletBalance debit(Long customerId, BigDecimal amount, String reference) {
        if (amount.signum() <= 0) throw new BusinessException("Debit amount must be positive");
        WalletBalance wb = balanceRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("No wallet for customer " + customerId));
        if (wb.getBalance().compareTo(amount) < 0) {
            throw new BusinessException("Insufficient wallet balance");
        }
        wb.setBalance(wb.getBalance().subtract(amount));
        wb.setUpdatedAt(LocalDateTime.now());
        balanceRepository.save(wb);

        recordTx(customerId, "DEBIT", amount, wb.getBalance(), reference);
        return wb;
    }

    @Transactional(readOnly = true)
    public WalletBalance balance(Long customerId) {
        return balanceRepository.findByCustomerId(customerId).orElse(null);
    }

    @Transactional(readOnly = true)
    public java.util.List<WalletTransaction> transactions(Long customerId) {
        return transactionRepository.findByCustomerId(customerId);
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

    /**
     * Offset-paginated transactions, newest first (small histories).
     */
    @Transactional(readOnly = true)
    public java.util.List<WalletTransaction> transactionsPage(Long customerId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), com.bhukkad.common.util.PaginationUtils.MAX_PAGE_SIZE);
        return transactionRepository.pageNewestFirst(customerId,
                org.springframework.data.domain.PageRequest.of(safePage, safeSize));
    }

    /**
     * Cursor page of transactions, newest first (unbounded histories).
     */
    @Transactional(readOnly = true)
    public CursorPage transactionsByCursor(Long customerId, String cursor, int size) {
        com.bhukkad.common.util.CursorUtils.OrderCursor c =
                com.bhukkad.common.util.CursorUtils.decode(cursor).orElse(null);
        LocalDateTime cursorCreatedAt = c != null ? c.createdAt() : com.bhukkad.common.util.CursorUtils.END_OF_TIME;
        Long cursorId = c != null ? c.id() : com.bhukkad.common.util.CursorUtils.END_OF_ID;
        int safeSize = Math.min(Math.max(size, 1), com.bhukkad.common.util.PaginationUtils.MAX_PAGE_SIZE);
        java.util.List<WalletTransaction> batch = transactionRepository.afterCursor(
                customerId, cursorCreatedAt, cursorId,
                org.springframework.data.domain.PageRequest.of(0, safeSize + 1));
        boolean hasNext = batch.size() > safeSize;
        java.util.List<WalletTransaction> pageItems = hasNext ? batch.subList(0, safeSize) : batch;
        String nextCursor = null;
        if (hasNext && !pageItems.isEmpty()) {
            WalletTransaction last = pageItems.get(pageItems.size() - 1);
            nextCursor = com.bhukkad.common.util.CursorUtils.encode(last.getCreatedAt(), last.getId());
        }
        return new CursorPage(pageItems, nextCursor, hasNext);
    }

    /** One cursor page of wallet transactions. */
    public record CursorPage(java.util.List<WalletTransaction> items, String nextCursor, boolean hasNext) {
    }
}
