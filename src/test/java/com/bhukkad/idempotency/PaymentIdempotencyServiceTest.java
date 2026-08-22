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

import java.time.LocalDateTime;
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
}
