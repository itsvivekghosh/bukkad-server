package com.bhukkad.order.service;

import com.bhukkad.common.saga.SagaCoordinator;
import com.bhukkad.common.saga.SagaInstance;
import com.bhukkad.common.saga.SagaInstanceRepository;
import com.bhukkad.common.saga.SagaStep;
import com.bhukkad.common.saga.SagaStepRepository;
import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.order.api.CreateOrderRequest;
import com.bhukkad.order.api.OrderItemRequest;
import com.bhukkad.order.api.OrderResponse;
import com.bhukkad.order.client.PaymentServiceClient;
import com.bhukkad.order.client.RestaurantClient;
import com.bhukkad.order.client.dto.ChargeResponse;
import com.bhukkad.order.client.dto.StockReservationLine;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderItemRepository;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.domain.OrderTimelineEvent;
import com.bhukkad.order.domain.OrderTimelineEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real saga execution (audit batch A): the RESERVE_STOCK / CHARGE_PAYMENT
 * steps must actually call the restaurant and payment services — a failing
 * reserve cancels the order before charging, a failing charge runs the
 * stock-release compensation, and the success path executes the steps in
 * order. Drives the production {@link OrderService#createOrder} through the
 * real platform-lib {@link SagaCoordinator}, mocking only the persistence
 * collaborators and the HTTP clients.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderSagaExecutionTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private OrderTimelineEventRepository timelineRepository;
    @Mock private SagaInstanceRepository sagaInstanceRepository;
    @Mock private SagaStepRepository sagaStepRepository;
    @Mock private OrderEventPublisher eventPublisher;
    @Mock private RestaurantClient restaurantClient;
    @Mock private PaymentServiceClient paymentServiceClient;
    @Mock private ObjectProvider<ServiceJwtAuthTokenProvider> tokenProvider;

    private OrderService service;

    @BeforeEach
    void wire() {
        SagaCoordinator coordinator = new SagaCoordinator(sagaInstanceRepository, sagaStepRepository);
        // Gate OFF: this suite pins the synchronous batch-A saga semantics.
        service = new OrderService(orderRepository, orderItemRepository, timelineRepository,
                coordinator, eventPublisher, restaurantClient, paymentServiceClient, tokenProvider,
                new com.bhukkad.order.OrderSagaProperties());

        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(7L);
            }
            return o;
        });
        when(orderItemRepository.findByOrderId(anyLong())).thenReturn(List.of());
        when(sagaInstanceRepository.findBySagaId(anyString())).thenReturn(null);
        when(sagaInstanceRepository.save(any(SagaInstance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sagaStepRepository.save(any(SagaStep.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sagaStepRepository.findBySagaInstanceIdAndStepOrder(any(), anyInt()))
                .thenAnswer(inv -> {
                    SagaInstance saga = inv.getArgument(0);
                    int order = inv.getArgument(1);
                    SagaStep step = SagaStep.newStep(saga, order, "step-" + order, "{}");
                    step.setId((long) order + 1);
                    return Optional.of(step);
                });
        when(tokenProvider.getIfAvailable()).thenReturn(null); // no service-mesh token configured
    }

    private CreateOrderRequest request() {
        return new CreateOrderRequest(1L, 2L, List.of(
                new OrderItemRequest(100L, "Paneer", new BigDecimal("240.00"), 1),
                new OrderItemRequest(101L, "Roti", new BigDecimal("20.00"), 2)));
    }

    private List<StockReservationLine> orderLines() {
        return List.of(
                StockReservationLine.of(100L, "Paneer", 1),
                StockReservationLine.of(101L, "Roti", 2));
    }

    private WebClientResponseException http(int status) {
        return WebClientResponseException.create(status, "boom", null,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private void stubReserveSuccess() {
        when(restaurantClient.reserveStock(any(), any())).thenReturn(Mono.just(orderLines()));
    }

    @Test
    void createOrder_happyPath_callsReserveThenChargeInOrderAndConfirms() {
        stubReserveSuccess();
        when(paymentServiceClient.charge(eq(7L), eq(1L), any(), anyString(), anyString(), any()))
                .thenReturn(Mono.just(new ChargeResponse(500L, "CHARGED")));

        OrderResponse response = service.createOrder(request());

        assertThat(response.status()).isEqualTo(Order.STATUS_CONFIRMED);
        assertThat(response.totalAmount()).isEqualByComparingTo("280.00");

        // steps executed in saga order against real contracts
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(restaurantClient, paymentServiceClient);
        ArgumentCaptor<List<StockReservationLine>> linesCaptor = ArgumentCaptor.forClass(List.class);
        inOrder.verify(restaurantClient).reserveStock(linesCaptor.capture(), any());
        assertThat(linesCaptor.getValue()).isEqualTo(orderLines());

        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> referenceCaptor = ArgumentCaptor.forClass(String.class);
        inOrder.verify(paymentServiceClient).charge(eq(7L), eq(1L), amountCaptor.capture(),
                eq(OrderService.SAGA_PAYMENT_METHOD), referenceCaptor.capture(), any());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo("280.00");
        assertThat(referenceCaptor.getValue()).isEqualTo("ORDER-7");

        verify(restaurantClient, never()).releaseStock(any(), any());
        verify(paymentServiceClient, never()).refund(any(), any(), any());
        assertTimeline("CONFIRMED");
        ArgumentCaptor<SagaInstance> sagaCaptor = ArgumentCaptor.forClass(SagaInstance.class);
        verify(sagaInstanceRepository, atLeastOnce()).save(sagaCaptor.capture());
        assertThat(sagaCaptor.getValue().getStatus()).isEqualTo(SagaInstance.STATUS_COMPLETED);
        verify(eventPublisher).orderCreated(7L, 1L, 2L);
        verify(eventPublisher).orderStatusChanged(7L, Order.STATUS_CONFIRMED);
    }

    @Test
    void createOrder_reserveFails_cancelsOrderWithoutCharging() {
        when(restaurantClient.reserveStock(any(), any())).thenReturn(Mono.error(http(409)));

        OrderResponse response = service.createOrder(request());

        assertThat(response.status()).isEqualTo(Order.STATUS_CANCELLED);
        verify(paymentServiceClient, never()).charge(any(), any(), any(), any(), any(), any());
        assertTimeline("FAILED");
        ArgumentCaptor<SagaInstance> sagaCaptor = ArgumentCaptor.forClass(SagaInstance.class);
        verify(sagaInstanceRepository, atLeastOnce()).save(sagaCaptor.capture());
        assertThat(sagaCaptor.getValue().getStatus()).isEqualTo(SagaInstance.STATUS_COMPENSATED);
        verify(eventPublisher).orderCreated(7L, 1L, 2L);
        verify(eventPublisher).orderStatusChanged(7L, Order.STATUS_CANCELLED);
    }

    @Test
    void createOrder_chargeFails_compensatesWithStockReleaseAndCancelsOrder() {
        stubReserveSuccess();
        when(restaurantClient.releaseStock(any(), any())).thenReturn(Mono.just(orderLines()));
        when(paymentServiceClient.charge(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(http(402)));

        OrderResponse response = service.createOrder(request());

        assertThat(response.status()).isEqualTo(Order.STATUS_CANCELLED);

        ArgumentCaptor<List<StockReservationLine>> released = ArgumentCaptor.forClass(List.class);
        verify(restaurantClient).releaseStock(released.capture(), any());
        assertThat(released.getValue()).isEqualTo(orderLines());

        assertTimeline("FAILED");
        ArgumentCaptor<SagaInstance> sagaCaptor = ArgumentCaptor.forClass(SagaInstance.class);
        verify(sagaInstanceRepository, atLeastOnce()).save(sagaCaptor.capture());
        assertThat(sagaCaptor.getValue().getStatus()).isEqualTo(SagaInstance.STATUS_COMPENSATED);

        ArgumentCaptor<SagaStep> failedStep = ArgumentCaptor.forClass(SagaStep.class);
        verify(sagaStepRepository, atLeastOnce()).save(failedStep.capture());
        assertThat(failedStep.getAllValues()).anySatisfy(step -> {
            assertThat(step.getStatus()).isEqualTo(SagaStep.STATUS_FAILED);
            assertThat(step.getStepOrder()).isEqualTo(1); // CHARGE_PAYMENT
        });
    }

    @Test
    void createOrder_chargeNotConfirmed_failsStepAndReleasesStock() {
        stubReserveSuccess();
        when(restaurantClient.releaseStock(any(), any())).thenReturn(Mono.just(orderLines()));
        // 202/PENDING (no CHARGED receipt) must count as a failed step.
        when(paymentServiceClient.charge(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());

        OrderResponse response = service.createOrder(request());

        assertThat(response.status()).isEqualTo(Order.STATUS_CANCELLED);
        verify(restaurantClient).releaseStock(any(), any());
    }

    private void assertTimeline(String eventType) {
        ArgumentCaptor<OrderTimelineEvent> captor = ArgumentCaptor.forClass(OrderTimelineEvent.class);
        verify(timelineRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(
                event -> assertThat(event.getEventType()).isEqualTo(eventType));
    }
}
