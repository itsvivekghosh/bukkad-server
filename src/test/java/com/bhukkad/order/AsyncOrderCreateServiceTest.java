package com.bhukkad.order;

import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AsyncOrderCreateService}.
 *
 * <p>The service is annotated {@code @Async}, but invoked directly here so the
 * success and failure bookkeeping runs synchronously and deterministically.
 */
@ExtendWith(MockitoExtension.class)
class AsyncOrderCreateServiceTest {

    private static final String JOB_ID = "job-1";
    private static final String IDEMPOTENCY_KEY = "idem-key-123";

    @Mock
    private OrderService orderService;
    @Mock
    private OrderCreateJobService orderCreateJobService;

    private AsyncOrderCreateService service;

    @BeforeEach
    void setUp() {
        service = new AsyncOrderCreateService(orderService, orderCreateJobService);
    }

    @Test
    void processOrderCreate_success_marksJobProcessingThenCompleted() {
        OrderRequest request = new OrderRequest();
        request.setRestaurantId(10L);
        OrderResponse created = new OrderResponse();
        created.setId(77L);
        when(orderService.createOrder(request, IDEMPOTENCY_KEY)).thenReturn(created);

        service.processOrderCreate(JOB_ID, request, IDEMPOTENCY_KEY);

        InOrder inOrder = inOrder(orderCreateJobService, orderService);
        inOrder.verify(orderCreateJobService).markProcessing(JOB_ID);
        inOrder.verify(orderService).createOrder(request, IDEMPOTENCY_KEY);
        inOrder.verify(orderCreateJobService).markCompleted(JOB_ID, created);
        verifyNoMoreInteractions(orderCreateJobService);
    }

    @Test
    void processOrderCreate_whenCreateOrderFails_marksJobFailedWithMessage() {
        OrderRequest request = new OrderRequest();
        request.setRestaurantId(10L);
        when(orderService.createOrder(request, IDEMPOTENCY_KEY))
                .thenThrow(new RuntimeException("payment declined"));

        assertDoesNotThrow(() -> service.processOrderCreate(JOB_ID, request, IDEMPOTENCY_KEY),
                "async worker must swallow the failure after recording it");

        InOrder inOrder = inOrder(orderCreateJobService);
        inOrder.verify(orderCreateJobService).markProcessing(JOB_ID);
        inOrder.verify(orderCreateJobService).markFailed(JOB_ID, "payment declined");
        verifyNoMoreInteractions(orderCreateJobService);
    }

    @Test
    void processOrderCreate_whenMarkProcessingFails_marksJobFailedWithoutCreatingOrder() {
        doThrow(new RuntimeException("redis down"))
                .when(orderCreateJobService).markProcessing(JOB_ID);

        service.processOrderCreate(JOB_ID, new OrderRequest(), IDEMPOTENCY_KEY);

        InOrder inOrder = inOrder(orderCreateJobService);
        inOrder.verify(orderCreateJobService).markProcessing(JOB_ID);
        inOrder.verify(orderCreateJobService).markFailed(JOB_ID, "redis down");
        verifyNoMoreInteractions(orderCreateJobService);
    }

    @Test
    void processOrderCreate_whenFailureHasNullMessage_forwardsNullToMarkFailed() {
        OrderRequest request = new OrderRequest();
        when(orderService.createOrder(request, IDEMPOTENCY_KEY))
                .thenThrow(new IllegalStateException());

        service.processOrderCreate(JOB_ID, request, IDEMPOTENCY_KEY);

        verify(orderCreateJobService).markFailed(JOB_ID, null);
        verify(orderCreateJobService).markProcessing(JOB_ID);
        verifyNoMoreInteractions(orderCreateJobService);
    }
}
