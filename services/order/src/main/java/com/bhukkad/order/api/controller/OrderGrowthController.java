package com.bhukkad.order.api.controller;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.entity.OrderTimelineEvent;
import com.bhukkad.order.domain.repository.OrderTimelineEventRepository;
import com.bhukkad.order.domain.service.impl.OrderService;
import com.bhukkad.order.domain.service.impl.OrderStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.bhukkad.order.domain.entity.Order;

/**
 * Order growth / timeline endpoints (port of monolith's
 * {@code OrderGrowthController}).
 *
 * <p>Timeline reads are owner-or-admin. Status transitions are a
 * fulfillment operation: they must never be callable by customers — a customer
 * marking an arbitrary order DELIVERED corrupts refunds, payouts, and
 * analytics. Invoice lives on {@link OrderAdjunctController} with identical
 * guard semantics (single canonical mapping).</p>
 */
@RestController
@RequestMapping("/api/v1/orders/{orderId}")
@RequiredArgsConstructor
public class OrderGrowthController {

    private final OrderTimelineEventRepository timelineRepository;
    private final OrderStatusService statusService;
    private final OrderService orderService;

    @GetMapping("/timeline")
    public List<OrderTimelineEvent> timeline(@AuthenticationPrincipal TokenPrincipal principal,
                                             @PathVariable Long orderId) {
        PrincipalGuard.requireSelfOrAdmin(principal,
                orderService.getOrder(orderId).customerId());
        return timelineRepository.findByOrderId(orderId);
    }

    @PostMapping("/status/{status}")
    @PreAuthorize("hasRole('ADMIN')")
    public Object transition(@PathVariable Long orderId, @PathVariable String status) {
        return statusService.transition(orderId, status);
    }
}
