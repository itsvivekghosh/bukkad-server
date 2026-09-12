package com.bhukkad.order.domain.service.impl;

import com.bhukkad.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.order.api.dto.response.OrderResponse;
import com.bhukkad.order.domain.service.impl.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Asynchronously creates orders in the background and tracks progress via
 * {@link OrderCreateJobService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncOrderCreateService {

    private final OrderService orderService;
    private final OrderCreateJobService orderCreateJobService;

    @Async("orderTaskExecutor")
    public void processOrderCreate(String jobId, CreateOrderRequest request) {
        try {
            orderCreateJobService.markProcessing(jobId);
            OrderResponse order = orderService.createOrder(request);
            orderCreateJobService.markCompleted(jobId, order);
            log.info("ASYNC_ORDER_CREATE_COMPLETED | jobId={} | orderId={}", jobId, order.id());
        } catch (Exception ex) {
            log.error("ASYNC_ORDER_CREATE_FAILED | jobId={} | error={}", jobId, ex.getMessage());
            orderCreateJobService.markFailed(jobId, ex.getMessage());
        }
    }
}
