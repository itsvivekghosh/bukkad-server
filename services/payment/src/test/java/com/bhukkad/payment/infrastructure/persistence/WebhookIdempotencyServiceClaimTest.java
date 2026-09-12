package com.bhukkad.payment.infrastructure.persistence;

import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookIdempotencyServiceClaimTest {

    @Mock private IdempotencyRecordRepository idempotencyRecordRepository;
    private WebhookIdempotencyService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new WebhookIdempotencyService(idempotencyRecordRepository);
    }

    @Test
    void alreadyProcessed_blankEventId_shortCircuitsFalse() {
        assertThat(service.isAlreadyProcessed(null)).isFalse();
        assertThat(service.isAlreadyProcessed("  ")).isFalse();
        verify(idempotencyRecordRepository, never())
                .findByScopeAndIdempotencyKey(any(), anyString());
    }

    @Test
    void alreadyProcessed_lookupUnderRazorpayWebhookScope() {
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.RAZORPAY_WEBHOOK, "evt-1"))
                .thenReturn(Optional.of(new IdempotencyRecord()));

        assertThat(service.isAlreadyProcessed("evt-1")).isTrue();
    }

    @Test
    void alreadyProcessed_unknownEventId_isFalse() {
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.RAZORPAY_WEBHOOK, "evt-2"))
                .thenReturn(Optional.empty());

        assertThat(service.isAlreadyProcessed("evt-2")).isFalse();
    }

    @Test
    void claim_blankEventId_writesNoDedupToken() {
        service.claim(null);
        service.claim("");

        verify(idempotencyRecordRepository, never()).saveAndFlush(any());
    }

    @Test
    void claim_writesCompletedRecordWithTtl() {
        service.claim("evt-3");

        ArgumentCaptor<IdempotencyRecord> captor =
                ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).saveAndFlush(captor.capture());
        IdempotencyRecord record = captor.getValue();
        assertThat(record.getIdempotencyKey()).isEqualTo("evt-3");
        assertThat(record.getScope()).isEqualTo(IdempotencyRecord.IdempotencyScope.RAZORPAY_WEBHOOK);
        assertThat(record.getStatus()).isEqualTo(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        assertThat(record.getExpiresAt()).isNotNull();
    }
}
