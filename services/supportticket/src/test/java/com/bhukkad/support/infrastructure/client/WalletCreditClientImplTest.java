package com.bhukkad.support.infrastructure.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class WalletCreditClientImplTest {

    @Mock
    private ServiceJwtAuthTokenProvider authTokenProvider;

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private WalletCreditClientImpl client() {
        return new WalletCreditClientImpl(builder, "http://payment:8080", authTokenProvider);
    }

    @Test
    void credit_shouldPostWalletRequestWithSerializedBodyAndServiceToken() {
        org.mockito.Mockito.lenient().when(authTokenProvider.serviceToken()).thenReturn("svc-token");

        server.expect(requestTo("http://payment:8080/api/v1/internal/wallet/credit"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, org.springframework.http.MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header("X-Service-Token", "svc-token"))
                .andExpect(jsonPath("$.customerId").value(7))
                .andExpect(jsonPath("$.amount").value(250.5))
                .andExpect(jsonPath("$.type").value("DISPUTE_REFUND:dispute-refund"))
                .andExpect(jsonPath("$.reference").value("dispute:REF-1:payment:42"))
                .andRespond(withSuccess());

        assertThatCode(() -> client().credit(7L, 250.5, "REF-1", 42L, "dispute-refund"))
                .doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void credit_shouldOmitServiceTokenHeaderWhenTokenUnavailable() {
        org.mockito.Mockito.lenient().when(authTokenProvider.serviceToken()).thenReturn(null);

        server.expect(requestTo("http://payment:8080/api/v1/internal/wallet/credit"))
                .andRespond(withSuccess());

        client().credit(1L, 10.0, "R", null, "d");
        server.verify();
    }

    @Test
    void credit_shouldSkipRemotewhenAmountIsNotPositive() {
        client().credit(1L, 0.0, "R", null, "d");
        client().credit(1L, -5.0, "R", null, "d");

        // No request was ever expected or made.
        server.verify();
    }

    @Test
    void credit_shouldWrapRestClientFailureInRuntimeException() {
        server.expect(requestTo("http://payment:8080/api/v1/internal/wallet/credit"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client().credit(3L, 99.0, "R2", 8L, "d"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Wallet credit failed; refund not applied");
        server.verify();
    }
}
