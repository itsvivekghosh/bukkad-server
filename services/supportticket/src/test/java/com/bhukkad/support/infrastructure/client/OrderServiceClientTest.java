package com.bhukkad.support.infrastructure.client;

import com.bhukkad.common.error.UpstreamUnavailableException;
import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.support.dto.OrderDetailDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class OrderServiceClientTest {

    @Mock
    private ServiceJwtAuthTokenProvider authTokenProvider;

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private OrderServiceClient client;

    @BeforeEach
    void setUp() {
        // Stub before construction: builders evaluate defaultHeaders at build().
        org.mockito.Mockito.lenient().when(authTokenProvider.serviceToken()).thenReturn("tok");
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OrderServiceClient(builder, "http://order:8080", authTokenProvider);
    }

    @Test
    void getOrderDetails_shouldMapResponseBodyToDto() {
        server.expect(requestTo("http://order:8080/api/v1/orders/42/details"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"orderId\":42,\"customerId\":7,\"status\":\"DELIVERED\","
                                + "\"totalAmount\":250.50,\"deliveredAt\":\"2026-01-02T10:15:00\","
                                + "\"estimatedDeliveryAt\":\"2026-01-02T10:00:00\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        OrderDetailDto dto = client.getOrderDetails(42L);

        assertThat(dto).isNotNull();
        assertThat(dto.getOrderId()).isEqualTo(42L);
        assertThat(dto.getCustomerId()).isEqualTo(7L);
        assertThat(dto.getStatus()).isEqualTo("DELIVERED");
        assertThat(dto.getTotalAmount()).isEqualByComparingTo(new BigDecimal("250.50"));
        server.verify();
    }

    @Test
    void getOrderDetails_shouldReturnNullOnUpstreamFailure() {
        server.expect(requestTo("http://order:8080/api/v1/orders/99/details"))
                .andRespond(withServerError());

        assertThat(client.getOrderDetails(99L)).isNull();
        server.verify();
    }

    @Test
    void getOrderDetails_shouldReturnNullWhenOrderServiceUnreachable() {
        // Unreachable host: the builder must not fail; the client swallows I/O errors.
        OrderServiceClient unreachable = new OrderServiceClient(
                RestClient.builder(), "http://127.0.0.1:1", authTokenProvider);

        assertThat(unreachable.getOrderDetails(1L)).isNull();
    }

    @Test
    void getOrderCustomerId_shouldResolveOwnerFromOracle() {
        server.expect(requestTo("http://order:8080/api/v1/internal/orders/42/customer"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .header("X-Service-Token", "tok"))
                .andRespond(withSuccess("{\"customerId\":7}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertThat(client.getOrderCustomerId(42L)).isEqualTo(7L);
        server.verify();
    }

    @Test
    void getOrderCustomerId_shouldReturnNullForGenuineNotFound() {
        server.expect(requestTo("http://order:8080/api/v1/internal/orders/404/customer"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThat(client.getOrderCustomerId(404L)).isNull();
        server.verify();
    }

    @Test
    void getOrderCustomerId_shouldFailClosedOnServerErrors() {
        server.expect(requestTo("http://order:8080/api/v1/internal/orders/5/customer"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getOrderCustomerId(5L))
                .isInstanceOf(UpstreamUnavailableException.class);
        server.verify();
    }

    @Test
    void getOrderCustomerId_shouldReturnNullWhenBodyAbsent() {
        server.expect(requestTo("http://order:8080/api/v1/internal/orders/6/customer"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NO_CONTENT));

        assertThat(client.getOrderCustomerId(6L)).isNull();
        server.verify();
    }

    @Test
    void orderCustomerRefRecord_shouldExposeCustomerId() {
        OrderServiceClient.OrderCustomerRef ref = new OrderServiceClient.OrderCustomerRef(11L);
        assertThat(ref.customerId()).isEqualTo(11L);
        assertThat(ref).hasToString("OrderCustomerRef[customerId=11]");
    }
}
