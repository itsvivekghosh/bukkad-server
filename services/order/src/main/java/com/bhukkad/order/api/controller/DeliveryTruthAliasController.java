package com.bhukkad.order.api.controller;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.entity.Order;
import com.bhukkad.order.domain.repository.OrderRepository;
import com.bhukkad.order.domain.service.impl.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Monolith-parity delivery-truth surface. The same ETA view as
 * {@code OrderAdjunctController#eta}; hosted under its absolute legacy path
 * ({@code /api/v1/delivery-truth/orders/{id}/eta}). A method-level alias
 * inside the adjunct previously double-prefixed to
 * {@code /api/v1/orders/api/v1/delivery-truth/...} and never matched — the
 * 100%-broken parity path returned 404.
 */
@RestController
@RequestMapping("/api/v1/delivery-truth/orders")
@RequiredArgsConstructor
public class DeliveryTruthAliasController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    @GetMapping("/{orderId}/eta")
    public Map<String, Object> eta(@AuthenticationPrincipal TokenPrincipal principal,
                                   @PathVariable Long orderId) {
        PrincipalGuard.requireSelfOrAdmin(principal,
                orderService.getOrder(orderId).customerId());
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        int baseMinutes = "OUT_FOR_DELIVERY".equals(order.getStatus()) ? 12 : 35;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("status", order.getStatus());
        body.put("etaMinutes", baseMinutes);
        body.put("confidence", "HIGH");
        return body;
    }
}
