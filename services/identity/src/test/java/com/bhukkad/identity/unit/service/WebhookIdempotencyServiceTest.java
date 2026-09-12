package com.bhukkad.identity.unit.service;

import com.bhukkad.identity.infrastructure.WebhookIdempotencyService;

import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookIdempotencyServiceTest {

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @InjectMocks
    private WebhookIdempotencyService service;

    @Test
    void isAlreadyProcessed_existingEvent_returnsTrue() {
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.RAZORPAY_WEBHOOK, "evt-1"))
                .thenReturn(Optional.of(new IdempotencyRecord()));

        assertThat(service.isAlreadyProcessed("evt-1")).isTrue();
    }

    @Test
    void isAlreadyProcessed_missingEvent_returnsFalse() {
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.RAZORPAY_WEBHOOK, "evt-1"))
                .thenReturn(Optional.empty());

        assertThat(service.isAlreadyProcessed("evt-1")).isFalse();
    }

    @Test
    void isAlreadyProcessed_blankEventId_returnsFalse() {
        assertThat(service.isAlreadyProcessed("")).isFalse();
        assertThat(service.isAlreadyProcessed(null)).isFalse();
    }

    @Test
    void markProcessed_savesRecord() {
        IdempotencyRecord saved = new IdempotencyRecord();
        saved.setIdempotencyKey("evt-1");
        when(idempotencyRecordRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .thenReturn(saved);

        assertThat(service.markProcessed("evt-1")).isTrue();
        verify(idempotencyRecordRepository).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void markProcessed_blankEventId_returnsTrueWithoutSaving() {
        assertThat(service.markProcessed("")).isTrue();
    }
}
