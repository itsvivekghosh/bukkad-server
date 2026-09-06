package com.bhukkad.order;

import com.bhukkad.common.saga.SagaAction;
import com.bhukkad.common.saga.SagaCoordinator;
import com.bhukkad.common.saga.SagaInstance;
import com.bhukkad.common.saga.SagaInstanceRepository;
import com.bhukkad.common.saga.SagaStepDefinition;
import com.bhukkad.order.api.CreateOrderRequest;
import com.bhukkad.order.api.OrderItemRequest;
import com.bhukkad.order.client.PaymentServiceClient;
import com.bhukkad.order.client.RestaurantClient;
import com.bhukkad.order.client.dto.ChargeResponse;
import com.bhukkad.order.client.dto.StockReservationLine;
import com.bhukkad.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test proving the order-creation saga (RESERVE_STOCK →
 * CHARGE_PAYMENT) runs through the real {@link SagaCoordinator} against a
 * Testcontainers PostgreSQL and produces a terminal COMPLETED or COMPENSATED
 * state. The platform-lib saga integration test covers the coordinator
 * itself; this test validates the order-service wiring. The (now REAL)
 * restaurant/payment HTTP collaborators are mocked at the client boundary —
 * their contracts are covered by PaymentServiceClientTest /
 * RestaurantClientTest.
 */
@SpringBootTest
class OrderSagaPostgresIntegrationTest extends AbstractOrderPostgresTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private SagaCoordinator sagaCoordinator;

    @Autowired
    private SagaInstanceRepository sagaInstanceRepository;

    @MockBean
    private RestaurantClient restaurantClient;

    @MockBean
    private PaymentServiceClient paymentServiceClient;

    @Test
    void createOrder_sagaCompletesSuccessfully() {
        when(restaurantClient.reserveStock(any(), any()))
                .thenReturn(Mono.just(List.of(StockReservationLine.of(100L, "Burger", 1))));
        when(paymentServiceClient.charge(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new ChargeResponse(5L, "CHARGED")));

        var request = new CreateOrderRequest(
                1L, 10L, List.of(
                new OrderItemRequest(100L, "Burger", BigDecimal.valueOf(12.50), 2),
                new OrderItemRequest(101L, "Fries", BigDecimal.valueOf(4.00), 1))
        );
        var response = orderService.createOrder(request);

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.customerId()).isEqualTo(1L);
        assertThat(response.restaurantId()).isEqualTo(10L);
        assertThat(response.totalAmount()).isEqualByComparingTo(BigDecimal.valueOf(29.00));

        SagaInstance saga = sagaInstanceRepository.findBySagaId(String.valueOf(response.id()));
        assertThat(saga).isNotNull();
        assertThat(saga.getStatus()).isEqualTo(SagaInstance.STATUS_COMPLETED);
    }

    @Test
    void createOrder_chargeFails_compensatesAndCancelsOrder() {
        when(restaurantClient.reserveStock(any(), any()))
                .thenReturn(Mono.just(List.of(StockReservationLine.of(100L, "Burger", 1))));
        when(restaurantClient.releaseStock(any(), any()))
                .thenReturn(Mono.just(List.of()));
        when(paymentServiceClient.charge(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("payment gateway down")));

        var request = new CreateOrderRequest(
                2L, 10L, List.of(new OrderItemRequest(100L, "Burger", BigDecimal.valueOf(12.50), 1)));
        var response = orderService.createOrder(request);

        assertThat(response.status()).isEqualTo("CANCELLED");
        verify(restaurantClient).releaseStock(any(), any());

        SagaInstance saga = sagaInstanceRepository.findBySagaId(String.valueOf(response.id()));
        assertThat(saga).isNotNull();
        assertThat(saga.getStatus()).isEqualTo(SagaInstance.STATUS_COMPENSATED);
    }

    @Test
    void failingSaga_triggersCompensation() {
        // Run a saga with a deliberately failing second step → compensation
        // of the first step. This validates the SagaCoordinator compensation
        // path through the same PG infrastructure the order service uses.
        SagaStepDefinition step1 = SagaStepDefinition.of("STEP1", new SagaAction() {
            @Override public String execute(String n, String p) { return "{\"compensated\":1}"; }
            @Override public void compensate(String n, String p, String c) { /* no-op, just test completion */ }
        });
        SagaStepDefinition step2 = SagaStepDefinition.of("STEP2", new SagaAction() {
            @Override public String execute(String n, String p) { throw new RuntimeException("Simulated failure"); }
            @Override public void compensate(String n, String p, String c) { }
        });

        SagaInstance saga = sagaCoordinator.executeSaga(
                "TEST_SAGA", "comp-test-" + System.nanoTime(), "{}", List.of(step1, step2));

        assertThat(saga.getStatus()).isEqualTo(SagaInstance.STATUS_COMPENSATED);
    }
}