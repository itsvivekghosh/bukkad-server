package com.bhukkad.order.api;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.OrderTimelineEvent;
import com.bhukkad.order.domain.OrderTimelineEventRepository;
import com.bhukkad.order.service.OrderInvoiceService;
import com.bhukkad.order.service.OrderService;
import com.bhukkad.order.service.OrderStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Order growth / timeline + invoice endpoints (port of monolith's
 * {@code OrderGrowthController}).
 *
 * <p>Timeline and invoice are owner-or-admin reads. Status transitions are a
 * fulfillment operation: they must never be callable by customers — a customer
 * marking an arbitrary order DELIVERED corrupts refunds, payouts, and
 * analytics.</p>
 */
@RestController
@RequestMapping("/api/v1/orders/{orderId}")
@RequiredArgsConstructor
public class OrderGrowthController {

    private final OrderTimelineEventRepository timelineRepository;
    private final OrderInvoiceService invoiceService;
    private final OrderStatusService statusService;
    private final OrderService orderService;

    @GetMapping("/timeline")
    public List<OrderTimelineEvent> timeline(@AuthenticationPrincipal TokenPrincipal principal,
                                             @PathVariable Long orderId) {
        PrincipalGuard.requireSelfOrAdmin(principal,
                orderService.getOrder(orderId).customerId());
        return timelineRepository.findByOrderId(orderId);
    }

    @GetMapping("/invoice")
    public Object invoice(@AuthenticationPrincipal TokenPrincipal principal,
                          @PathVariable Long orderId) {
        PrincipalGuard.requireSelfOrAdmin(principal,
                orderService.getOrder(orderId).customerId());
        return invoiceService.getByOrder(orderId);
    }

    @PostMapping("/status/{status}")
    @PreAuthorize("hasRole('ADMIN')")
    public Object transition(@PathVariable Long orderId, @PathVariable String status) {
        return statusService.transition(orderId, status);
    }
}
