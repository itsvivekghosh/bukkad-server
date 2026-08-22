package com.bhukkad.wallet;

import com.bhukkad.dto.response.CursorPagedResponse;
import com.bhukkad.dto.response.WalletTransactionResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.WalletTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link WalletQueryService#getTransactionsByCursor}.
 *
 * <p>The cursor logic is the new behaviour — the offset path is unchanged and
 * exercised elsewhere. These tests pin the three behaviours that matter for
 * production: the cursor round-trips through the repository keyset predicate,
 * the {@code hasNext} flag is computed off {@code size + 1} over-fetch, and a
 * cursor for an unknown customer still 404s instead of silently returning an
 * empty page.
 */
@ExtendWith(MockitoExtension.class)
class WalletQueryServiceCursorTest {

    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private CustomerRepository customerRepository;

    @InjectMocks private WalletQueryService service;

    @Test
    void firstPage_passesNullCursorAndReturnsHasNextTrue() {
        when(customerRepository.existsById(1L)).thenReturn(true);
        WalletTransaction older = tx(10L, LocalDateTime.now().minusDays(1));
        WalletTransaction newer = tx(11L, LocalDateTime.now());
        when(walletTransactionRepository.findByCustomerIdAfterCursor(
                eq(1L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(newer, older));

        CursorPagedResponse<WalletTransactionResponse> page =
                service.getTransactionsByCursor(1L, null, 1);

        assertTrue(page.isHasNext());
        assertEquals(1, page.getItems().size());
        assertNotNull(page.getNextCursor(), "hasNext implies a non-null cursor");
        verify(walletTransactionRepository)
                .findByCustomerIdAfterCursor(eq(1L), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void pageWithNoMoreResults_hasNextFalseAndNullCursor() {
        when(customerRepository.existsById(1L)).thenReturn(true);
        WalletTransaction only = tx(10L, LocalDateTime.now());
        when(walletTransactionRepository.findByCustomerIdAfterCursor(
                eq(1L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(only));

        CursorPagedResponse<WalletTransactionResponse> page =
                service.getTransactionsByCursor(1L, null, 5);

        assertEquals(1, page.getItems().size());
        assertEquals(false, page.isHasNext());
        assertNull(page.getNextCursor());
    }

    @Test
    void unknownCustomer_throwsResourceNotFoundAndDoesNotQueryRepo() {
        when(customerRepository.existsById(99L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class,
                () -> service.getTransactionsByCursor(99L, null, 20));

        verify(walletTransactionRepository, never())
                .findByCustomerIdAfterCursor(any(), any(), any(), any());
    }

    private WalletTransaction tx(long id, LocalDateTime createdAt) {
        try {
            WalletTransaction t = new WalletTransaction();
            setField(t, "id", id);
            setField(t, "createdAt", createdAt);
            setField(t, "type", WalletTransaction.TransactionType.TOP_UP);
            setField(t, "amount", 100.0);
            setField(t, "balanceAfter", 200.0);
            setField(t, "description", "test");
            Customer c = new Customer();
            setField(c, "id", 1L);
            setField(t, "customer", c);
            return t;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
