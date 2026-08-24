package com.bhukkad.idempotency;

import com.bhukkad.entity.Payment;
import com.bhukkad.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentIdempotencyServiceTest {

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;
    @Mock
    private IdempotencyService idempotencyService;

    private PaymentIdempotencyService paymentIdempotencyService;

    @BeforeEach
    void setUp() {
        paymentIdempotencyService = new PaymentIdempotencyService(idempotencyRecordRepository, idempotencyService, new com.fasterxml.jackson.databind.ObjectMapper());
        // The Redis lock is best-effort: default to acquired so DB-only tests
        // exercise the durable guard path. Tests that verify lock rejection
        // override this stub.
        lenient().when(idempotencyService.tryAcquireLock(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
    }

    @Test
    void findCompletedPayment_withCachedResponse_returnsCached() {
        Payment payment = new Payment();
        payment.setId(1L);
        when(idempotencyService.getPaymentResult("pay-key", Payment.class))
                .thenReturn(Optional.of(payment));

        Optional<Payment> result = paymentIdempotencyService.findCompletedPayment("pay-key");

        assertTrue(result.isPresent());
        assertEquals(1L, result.get().getId());
        verify(idempotencyRecordRepository, never()).findByScopeAndIdempotencyKey(any(), anyString());
    }

    @Test
    void findCompletedPayment_withEmptyKey_returnsEmpty() {
        assertTrue(paymentIdempotencyService.findCompletedPayment("").isEmpty());
        assertTrue(paymentIdempotencyService.findCompletedPayment(null).isEmpty());
    }

    @Test
    void beginPaymentProcess_newRecord_createsInProgress() {
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "new-key"))
                .thenReturn(Optional.empty());

        paymentIdempotencyService.beginPaymentProcess("new-key");

        ArgumentCaptor<IdempotencyRecord> captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).save(captor.capture());
        IdempotencyRecord saved = captor.getValue();
        assertEquals("new-key", saved.getIdempotencyKey());
        assertEquals(IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, saved.getScope());
        assertEquals(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS, saved.getStatus());
        assertNotNull(saved.getExpiresAt());
    }

    @Test
    void beginPaymentProcess_withDuplicateKey_throwsException() {
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "duplicate"))
                .thenReturn(Optional.empty());

        doThrow(new DataIntegrityViolationException("Duplicate"))
                .when(idempotencyRecordRepository).save(any(IdempotencyRecord.class));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                paymentIdempotencyService.beginPaymentProcess("duplicate"));
        assertTrue(ex.getMessage().contains("Duplicate payment request"));
    }

    @Test
    void beginPaymentProcess_withInProgressRecord_throwsException() {
        IdempotencyRecord existing = new IdempotencyRecord();
        existing.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);

        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "in-progress"))
                .thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                paymentIdempotencyService.beginPaymentProcess("in-progress"));
        assertTrue(ex.getMessage().contains("already being processed"));
    }

    @Test
    void beginPaymentProcess_whenRedisLockNotAcquired_throwsDuplicate() {
        // Another instance is already processing this key: the Redis SETNX lock
        // is not acquired, so the call is rejected before touching the DB.
        when(idempotencyService.tryAcquireLock(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                paymentIdempotencyService.beginPaymentProcess("locked-key"));
        assertTrue(ex.getMessage().contains("Duplicate payment request"));
        verify(idempotencyRecordRepository, never()).findByScopeAndIdempotencyKey(any(), anyString());
    }

    @Test
    void completePaymentProcess_marksRecordCompletedAndCaches() {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);

        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "pay-key"))
                .thenReturn(Optional.of(record));

        Payment payment = new Payment();
        payment.setId(7L);

        paymentIdempotencyService.completePaymentProcess("pay-key", payment);

        assertEquals(IdempotencyRecord.IdempotencyStatus.COMPLETED, record.getStatus());
        verify(idempotencyService).storePaymentResult(eq("pay-key"), eq(payment), any());
    }

    @Test
    void completePaymentProcess_withNullKey_doesNothing() {
        paymentIdempotencyService.completePaymentProcess(null, new Payment());
        verify(idempotencyRecordRepository, never()).findByScopeAndIdempotencyKey(any(), anyString());
    }

    @Test
    void failPaymentProcess_setsFailed() {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);

        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "fail-key"))
                .thenReturn(Optional.of(record));

        paymentIdempotencyService.failPaymentProcess("fail-key");

        assertEquals(IdempotencyRecord.IdempotencyStatus.FAILED, record.getStatus());
        verify(idempotencyRecordRepository).save(record);
    }

    @Test
    void failPaymentProcess_withNullKey_doesNothing() {
        paymentIdempotencyService.failPaymentProcess(null);
        verify(idempotencyRecordRepository, never()).findByScopeAndIdempotencyKey(any(), anyString());
    }

    // ==================== additional coverage ====================

    @Test
    void findCompletedPayment_completedRecordInDb_deserializesPayload() {
        when(idempotencyService.getPaymentResult("db-key", Payment.class))
                .thenReturn(Optional.empty());

        IdempotencyRecord record = new IdempotencyRecord();
        record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setResponsePayload("{\"id\":55,\"amount\":100.0,\"status\":\"PENDING\"}");
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "db-key"))
                .thenReturn(Optional.of(record));

        Optional<Payment> result = paymentIdempotencyService.findCompletedPayment("db-key");

        assertTrue(result.isPresent());
        assertEquals(55L, result.get().getId());
    }

    @Test
    void findCompletedPayment_nonCompletedRecord_returnsEmpty() {
        when(idempotencyService.getPaymentResult("pending-key", Payment.class))
                .thenReturn(Optional.empty());

        IdempotencyRecord record = new IdempotencyRecord();
        record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "pending-key"))
                .thenReturn(Optional.of(record));

        assertTrue(paymentIdempotencyService.findCompletedPayment("pending-key").isEmpty());
    }

    @Test
    void findCompletedPayment_corruptPayload_returnsEmpty() {
        when(idempotencyService.getPaymentResult("corrupt-key", Payment.class))
                .thenReturn(Optional.empty());

        IdempotencyRecord record = new IdempotencyRecord();
        record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setResponsePayload("{not-valid-json");
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "corrupt-key"))
                .thenReturn(Optional.of(record));

        // Corrupt payloads are logged and skipped rather than failing the read
        assertTrue(paymentIdempotencyService.findCompletedPayment("corrupt-key").isEmpty());
    }

    @Test
    void beginPaymentProcess_withBlankKey_doesNothing() {
        paymentIdempotencyService.beginPaymentProcess("");

        verify(idempotencyRecordRepository, never()).findByScopeAndIdempotencyKey(any(), anyString());
    }

    @Test
    void beginPaymentProcess_completedRecord_isIdempotentNoOp() {
        IdempotencyRecord existing = new IdempotencyRecord();
        existing.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, "done"))
                .thenReturn(Optional.of(existing));

        assertDoesNotThrow(() -> paymentIdempotencyService.beginPaymentProcess("done"));
        verify(idempotencyRecordRepository, never()).save(any());
    }
}
