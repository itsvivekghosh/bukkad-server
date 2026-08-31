package com.bhukkad.order.api;

import com.bhukkad.order.domain.OrderTimelineEvent;
import com.bhukkad.order.domain.OrderTimelineEventRepository;
import com.bhukkad.order.service.OrderInvoiceService;
import com.bhukkad.order.service.OrderStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Order growth / timeline + invoice endpoints (port of monolith's
 * {@code OrderGrowthController}).
 */
@RestController
@RequestMapping("/api/v1/orders/{orderId}")
@RequiredArgsConstructor
public class OrderGrowthController {

    private final OrderTimelineEventRepository timelineRepository;
    private final OrderInvoiceService invoiceService;
    private final OrderStatusService statusService;

    @GetMapping("/timeline")
    public List<OrderTimelineEvent> timeline(@PathVariable Long orderId) {
        return timelineRepository.findByOrderId(orderId);
    }

    @GetMapping("/invoice")
    public Object invoice(@PathVariable Long orderId) {
        return invoiceService.getByOrder(orderId);
    }

    @PostMapping("/status/{status}")
    public Object transition(@PathVariable Long orderId, @PathVariable String status) {
        return statusService.transition(orderId, status);
    }
}