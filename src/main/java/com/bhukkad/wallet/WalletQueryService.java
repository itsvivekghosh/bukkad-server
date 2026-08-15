package com.bhukkad.wallet;

import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.dto.response.WalletTransactionResponse;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.WalletTransactionRepository;
import com.bhukkad.util.PaginationUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
