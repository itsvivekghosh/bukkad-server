package com.bhukkad.order.api;

import com.bhukkad.order.service.OrderAssistService;
import com.bhukkad.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Order-assist: reorder from history (port of monolith's
 * {@code OrderAssistController}).
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}")
@RequiredArgsConstructor
public class OrderAssistController {

    private final OrderAssistService assistService;
    private final OrderService orderService;

    @PostMapping("/reorder/{pastOrderId}")
    public Object reorder(@PathVariable Long customerId, @PathVariable Long pastOrderId) {
        assistService.reorder(customerId, pastOrderId);
        var request = assistService.toCreateRequest(pastOrderId);
        return orderService.createOrder(request);
    }
}