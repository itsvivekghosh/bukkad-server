package com.bhukkad.growth.domain.service;

import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.growth.api.GrowthExceptionHandler;
import com.bhukkad.growth.api.LoyaltyDailyCapExceededException;
import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.domain.repository.LoyaltyCreditIdempotencyRepository;
import com.bhukkad.growth.domain.repository.LoyaltyPointsLedgerRepository;
import com.bhukkad.growth.domain.service.LoyaltyCreditService.CreditOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoyaltyCreditServiceUnitTest {

    @Mock private LoyaltyService loyaltyService;
    @Mock private LoyaltyCreditIdempotencyRepository idempotencyRepository;
    @Mock private LoyaltyPointsLedgerRepository ledgerRepository;

    private final GrowthProperties properties = new GrowthProperties();
    private LoyaltyCreditService service() {
        return new LoyaltyCreditService(loyaltyService, idempotencyRepository, ledgerRepository, properties);
    }

    @Test
    void credit_knownKey_returnsReplay() {
        when(idempotencyRepository.findByKey("k1"))
                .thenReturn(Optional.of(new IdempotencyRecord()));

        CreditOutcome outcome = service().credit(1L, 10, "X", "k1");

        assertThat(outcome.replay()).isTrue();
        assertThat(outcome.freshCredit()).isFalse();
        verify(loyaltyService, never()).creditPoints(anyLong(), anyInt(), anyString(), any());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    @Test
    void credit_withinCap_claimsAndCredits() {
        when(idempotencyRepository.findByKey("order:42")).thenReturn(Optional.empty());
        when(ledgerRepository.creditedSince(eq(1L), any(LocalDateTime.class))).thenReturn(9_950L);
        when(idempotencyRepository.insertIfAbsent(eq("order:42"), eq(1L), anyString(), anyString(),
                any(LocalDateTime.class))).thenReturn(1);

        CreditOutcome outcome = service().credit(1L, 50, "ORDER_REWARD", "order:42");

        assertThat(outcome.freshCredit()).isTrue();
        verify(loyaltyService).creditPoints(1L, 50, "ORDER_REWARD", "order:42");
    }

    @Test
    void credit_overDailyCap_throws422MappedException() throws Exception {
        when(idempotencyRepository.findByKey("k2")).thenReturn(Optional.empty());
        when(ledgerRepository.creditedSince(eq(1L), any(LocalDateTime.class))).thenReturn(9_990L);

        LoyaltyCreditService service = service();
        assertThatThrownBy(() -> service.credit(1L, 20, "X", "k2"))
                .isInstanceOf(LoyaltyDailyCapExceededException.class);
        verify(idempotencyRepository, never()).insertIfAbsent(anyString(), anyLong(), anyString(),
                anyString(), any(LocalDateTime.class));

        // The growth advice maps it to an honest 422.
        var response = new GrowthExceptionHandler().dailyCap(
                new LoyaltyDailyCapExceededException("cap"));
        assertThat(response.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void credit_concurrentClaimLosers_getDuplicate() {
        when(idempotencyRepository.findByKey("race")).thenReturn(Optional.empty());
        when(idempotencyRepository.insertIfAbsent(eq("race"), eq(1L), anyString(), anyString(),
                any(LocalDateTime.class))).thenReturn(0);

        LoyaltyCreditService service = service();
        assertThatThrownBy(() -> service.credit(1L, 10, "X", "race"))
                .isInstanceOf(DuplicateRequestException.class);
        verify(loyaltyService, never()).creditPoints(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void credit_longIdempotencyKey_creditsWithStableDigestReference() {
        when(idempotencyRepository.findByKey(anyString())).thenReturn(Optional.empty());
        when(idempotencyRepository.insertIfAbsent(anyString(), anyLong(), anyString(), anyString(),
                any(LocalDateTime.class))).thenReturn(1);

        String longKey = "x".repeat(80);
        service().credit(1L, 10, "X", longKey);

        ArgumentCaptor<String> ledgerReference = ArgumentCaptor.forClass(String.class);
        verify(loyaltyService).creditPoints(eq(1L), eq(10), eq("X"), ledgerReference.capture());
        assertThat(ledgerReference.getValue()).hasSize(50).doesNotContain("x");

        // digest is stable across calls
        service().credit(1L, 10, "X", longKey);
        ArgumentCaptor<String> second = ArgumentCaptor.forClass(String.class);
        verify(loyaltyService, org.mockito.Mockito.times(2))
                .creditPoints(eq(1L), eq(10), eq("X"), second.capture());
        assertThat(second.getAllValues().get(1)).isEqualTo(ledgerReference.getValue());
    }
}
