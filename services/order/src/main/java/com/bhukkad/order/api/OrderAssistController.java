package com.bhukkad.order.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.service.OrderAssistService;
import com.bhukkad.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Order-assist: reorder from history (port of monolith's
 * {@code OrderAssistController}).
 *
 * <p>Ownership is enforced twice: the path customerId must match the JWT
 * subject, and the past order must belong to that customer — otherwise the
 * endpoint would copy another user's order items or create an order charged
 * to a stranger.</p>
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}")
@RequiredArgsConstructor
public class OrderAssistController {

    private final OrderAssistService assistService;
    private final OrderService orderService;

    @PostMapping("/reorder/{pastOrderId}")
    public Object reorder(@AuthenticationPrincipal TokenPrincipal principal,
                          @PathVariable Long customerId,
                          @PathVariable Long pastOrderId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        Object pastOrder = orderService.getOrder(pastOrderId);
        if (pastOrder instanceof com.bhukkad.order.api.OrderResponse response
                && !customerId.equals(response.customerId())) {
            throw new BusinessException("Cannot reorder another customer's order");
        }
        assistService.reorder(customerId, pastOrderId);
        var request = assistService.toCreateRequest(pastOrderId);
        return orderService.createOrder(request);
    }
}
