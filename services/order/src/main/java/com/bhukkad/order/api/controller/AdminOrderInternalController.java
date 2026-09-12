package com.bhukkad.order.api.controller;

import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.common.scan.AllowFullScan;
import com.bhukkad.order.domain.entity.Order;
import com.bhukkad.order.domain.repository.OrderRepository;
import com.bhukkad.order.domain.service.impl.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.bhukkad.order.api.dto.response.OrderResponse;

/**
 * Platform-admin order console (service-internal surface): paged listing over
 * all orders for the ops dashboard. ADMIN-gated via method security; reachable
 * only on the service mesh — the gateway routes the admin console through the
 * {@code /api/v1/internal/admin/orders} proxy.
 */
@RestController
@RequestMapping("/api/v1/internal/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminOrderInternalController {

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    @GetMapping("/orders")
    @Transactional(readOnly = true)
    @AllowFullScan(reason = "G-6 reviewed: paged — page/size clamped to (0..) x 1..100 before the query")
    public Page<OrderResponse> orders(@AuthenticationPrincipal TokenPrincipal principal,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "10") int size) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated admin required");
        }
        return orderRepository
                .findAll(PageRequest.of(Math.max(page, 0),
                        Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "id")))
                .map(orderService::toResponseCompat);
    }
}
