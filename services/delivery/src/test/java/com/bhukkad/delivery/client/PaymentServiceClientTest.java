package com.bhukkad.delivery.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.delivery.infrastructure.client.PaymentServiceClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceClientTest {

    @Mock private RestTemplate restTemplate;
    @Mock private ServiceJwtAuthTokenProvider authTokenProvider;

    private PaymentServiceClient client() {
        return new PaymentServiceClient(
                restTemplate, "http://payment:8080", authTokenProvider);
    }

    @Test
    void getCodWallet_getsUnauthenticatedReadPath() {
        when(restTemplate.getForObject(anyString(), eq(Map.class), any(Object[].class)))
                .thenReturn(Map.of("balance", 500));

        assertThat(client().getCodWallet(3L)).containsEntry("balance", 500);
        verify(restTemplate).getForObject(
                eq("http://payment:8080/api/v1/internal/delivery/cod-wallet/{agentId}"),
                eq(Map.class), eq(3L));
    }

    @Test
    void getCodWallet_upstreamFailure_wrapsAsUnavailable() {
        when(restTemplate.getForObject(anyString(), eq(Map.class), any(Object[].class)))
                .thenThrow(new ResourceAccessException("connect refused"));

        assertThatThrownBy(() -> client().getCodWallet(3L))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Payment service unavailable")
                .hasCauseInstanceOf(ResourceAccessException.class);
    }

    @Test
    void walletMutations_carryServiceTokenHeader() {
        when(authTokenProvider.serviceToken()).thenReturn("svc-jwt");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(Map.class), any(Object[].class)))
                .thenReturn(new ResponseEntity<>(Map.of("ok", true), HttpStatus.OK));

        PaymentServiceClient client = client();
        client.creditCodWallet(1L, new BigDecimal("10.00"));
        client.debitCodWallet(1L, new BigDecimal("4.00"));
        client.markEarningPaid(9L);

        ArgumentCaptor<HttpEntity<?>> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, org.mockito.Mockito.times(3)).exchange(anyString(),
                eq(HttpMethod.POST), entity.capture(), eq(Map.class), any(Object[].class));
        for (HttpEntity<?> e : entity.getAllValues()) {
            HttpHeaders headers = e.getHeaders();
            assertThat(headers.getFirst("X-Service-Token")).isEqualTo("svc-jwt");
            assertThat(headers.getContentType().toString()).startsWith("application/json");
        }
    }

    @Test
    void authHeaders_omitTokenWhenProviderUnavailable() {
        when(authTokenProvider.serviceToken()).thenReturn(null);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(Map.class), any(Object[].class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));

        client().creditCodWallet(1L, BigDecimal.ONE);

        ArgumentCaptor<HttpEntity<?>> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entity.capture(),
                eq(Map.class), any(Object[].class));
        assertThat(entity.getValue().getHeaders().get("X-Service-Token")).isNull();
    }

    @Test
    void earningsRoutes_buildUriAndCarryToken() {
        when(authTokenProvider.serviceToken()).thenReturn("svc");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", 7), HttpStatus.OK));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(List.class), any(Object[].class)))
                .thenReturn(new ResponseEntity<>(List.of(Map.of("id", 7)), HttpStatus.OK));

        PaymentServiceClient client = client();
        assertThat(client.recordEarning(2L, 8L, new BigDecimal("33.00"))).containsEntry("id", 7);
        assertThat(client.getEarnings(2L)).hasSize(1);

        verify(restTemplate).exchange(
                eq("http://payment:8080/api/v1/internal/delivery/earnings/record"
                        + "?agentId=2&orderId=8&amount=33.00"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
        verify(restTemplate).exchange(
                eq("http://payment:8080/api/v1/internal/delivery/earnings/{agentId}"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(List.class), eq(2L));
    }

    @Test
    void transientFailures_mapToUnavailableOnEverySurface() {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class),
                eq(Map.class), any(Object[].class)))
                .thenThrow(new ResourceAccessException("reset"));
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class),
                eq(List.class), any(Object[].class)))
                .thenThrow(new ResourceAccessException("reset"));
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class),
                eq(Map.class)))
                .thenThrow(new ResourceAccessException("reset"));

        PaymentServiceClient client = client();
        assertThatThrownBy(() -> client.creditCodWallet(1L, BigDecimal.ONE))
                .isInstanceOf(RuntimeException.class).hasMessage("Payment service unavailable");
        assertThatThrownBy(() -> client.debitCodWallet(1L, BigDecimal.ONE))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> client.recordEarning(1L, 2L, BigDecimal.ONE))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> client.getEarnings(1L))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> client.markEarningPaid(1L))
                .isInstanceOf(RuntimeException.class);
        verify(restTemplate, never()).getForObject(anyString(), eq(Map.class), any(Object[].class));
    }

    @Test
    void debitWallet_successBodyIsUnwrapped() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(Map.class), any(Object[].class)))
                .thenReturn(new ResponseEntity<>(Map.of("balance", 20), HttpStatus.OK));

        assertThat(client().debitCodWallet(5L, new BigDecimal("5.00")))
                .containsEntry("balance", 20);
    }
}
