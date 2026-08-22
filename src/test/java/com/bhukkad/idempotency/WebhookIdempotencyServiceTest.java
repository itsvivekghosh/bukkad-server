package com.bhukkad.idempotency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebhookIdempotencyService} — the dedup layer that
 * prevents Razorpay webhook replay from double-crediting payments.
 */
@ExtendWith(MockitoExtension.class)
class WebhookIdempotencyServiceTest {

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    private WebhookIdempotencyService service;

    @Test
    void isAlreadyProcessed_returnsTrue_whenRecordExists() {
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.RAZORPAY_WEBHOOK, "evt_abc"))
                .thenReturn(Optional.of(new IdempotencyRecord()));

        service = new WebhookIdempotencyService(idempotencyRecordRepository);
        assertTrue(service.isAlreadyProcessed("evt_abc"));
    }

    @Test
    void isAlreadyProcessed_returnsFalse_whenNoRecord() {
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.RAZORPAY_WEBHOOK, "evt_missing"))
                .thenReturn(Optional.empty());

        service = new WebhookIdempotencyService(idempotencyRecordRepository);
        assertFalse(service.isAlreadyProcessed("evt_missing"));
    }

    @Test
    void isAlreadyProcessed_returnsFalse_forNull() {
        service = new WebhookIdempotencyService(idempotencyRecordRepository);
        assertFalse(service.isAlreadyProcessed(null));
    }

    @Test
    void markProcessed_returnsTrue_onFirstCall() {
        when(idempotencyRecordRepository.saveAndFlush(any())).thenReturn(new IdempotencyRecord());

        service = new WebhookIdempotencyService(idempotencyRecordRepository);
        assertTrue(service.markProcessed("evt_first"));
    }

    @Test
    void markProcessed_throwsOnDuplicate() {
        // The duplicate insert throws; the exception must propagate out of the
        // REQUIRES_NEW transaction so the caller can acknowledge the duplicate.
        when(idempotencyRecordRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        service = new WebhookIdempotencyService(idempotencyRecordRepository);
        assertThrows(DataIntegrityViolationException.class,
                () -> service.markProcessed("evt_dup"));
    }

    @Test
    void markProcessed_returnsTrue_forNull() {
        service = new WebhookIdempotencyService(idempotencyRecordRepository);
        assertTrue(service.markProcessed(null));
    }

    @Test
    void markProcessed_returnsTrue_forEmptyString() {
        service = new WebhookIdempotencyService(idempotencyRecordRepository);
        assertTrue(service.markProcessed(""));
    }
}