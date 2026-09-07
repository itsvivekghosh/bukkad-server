package com.bhukkad.order.api;

import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.service.OrderService;
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
