package com.bhukkad.order;

import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncOrderCreateService {

    private final OrderService orderService;
    private final OrderCreateJobService orderCreateJobService;

    @Async("orderTaskExecutor")
    public void processOrderCreate(String jobId, OrderRequest request, String idempotencyKey) {
        try {
            orderCreateJobService.markProcessing(jobId);
            OrderResponse order = orderService.createOrder(request, idempotencyKey);
            orderCreateJobService.markCompleted(jobId, order);
            log.info("ASYNC_ORDER_CREATE_COMPLETED | jobId={} | orderId={}", jobId, order.getId());
        } catch (Exception ex) {
            log.error("ASYNC_ORDER_CREATE_FAILED | jobId={} | error={}", jobId, ex.getMessage());
            orderCreateJobService.markFailed(jobId, ex.getMessage());
        }
    }
}
