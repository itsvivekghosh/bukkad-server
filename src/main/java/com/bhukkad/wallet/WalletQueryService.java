package com.bhukkad.wallet;

import com.bhukkad.dto.response.CursorPagedResponse;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.dto.response.WalletTransactionResponse;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.WalletTransactionRepository;
import com.bhukkad.util.CursorUtils;
import com.bhukkad.util.PaginationUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only wallet transaction queries for customer wallet history.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletQueryService {

    private final WalletTransactionRepository walletTransactionRepository;
    private final CustomerRepository customerRepository;

    /**
     * Returns paginated wallet transactions for a customer.
     *
     * @param customerId customer identifier
     * @param page       page index (0-based)
     * @param size       page size
     * @return paged transaction history
     */
    public PagedResponse<WalletTransactionResponse> getTransactions(Long customerId, int page, int size) {
        if (!customerRepository.existsById(customerId)) {
            throw new ResourceNotFoundException("Customer not found");
        }

        var result = walletTransactionRepository.findByCustomerIdOrderByCreatedAtDesc(
                customerId,
                PaginationUtils.page(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        return PagedResponse.from(result.map(this::toResponse));
    }

    /**
     * Cursor-paginated wallet transactions — preferred over
     * {@link #getTransactions(Long, int, int)} for unbounded histories because
     * the keyset predicate keeps query cost constant regardless of scroll depth.
     */
    public CursorPagedResponse<WalletTransactionResponse> getTransactionsByCursor(Long customerId, String cursor, int size) {
        if (!customerRepository.existsById(customerId)) {
            throw new ResourceNotFoundException("Customer not found");
        }
        CursorUtils.OrderCursor c = CursorUtils.decode(cursor).orElse(null);
        int safeSize = Math.min(Math.max(size, 1), PaginationUtils.MAX_PAGE_SIZE);
        List<WalletTransaction> batch = walletTransactionRepository.findByCustomerIdAfterCursor(
                customerId,
                c != null ? c.createdAt() : null,
                c != null ? c.id() : null,
                PageRequest.of(0, safeSize + 1));
        return toCursorPage(batch, safeSize);
    }

    private CursorPagedResponse<WalletTransactionResponse> toCursorPage(List<WalletTransaction> batch, int size) {
        boolean hasNext = batch.size() > size;
        List<WalletTransaction> page = hasNext ? batch.subList(0, size) : batch;
        String nextCursor = null;
        if (hasNext && !page.isEmpty()) {
            WalletTransaction last = page.get(page.size() - 1);
            nextCursor = CursorUtils.encode(last.getCreatedAt(), last.getId());
        }
        List<WalletTransactionResponse> items = page.stream().map(this::toResponse).toList();
        return CursorPagedResponse.of(items, nextCursor, hasNext);
    }

    private WalletTransactionResponse toResponse(WalletTransaction tx) {
        return WalletTransactionResponse.builder()
                .id(tx.getId())
                .type(tx.getType().name())
                .amount(tx.getAmount())
                .balanceAfter(tx.getBalanceAfter())
                .description(tx.getDescription())
                .createdAt(tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : null)
                .build();
    }
}
