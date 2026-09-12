package com.bhukkad.order.api.controller;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.entity.Order;
import com.bhukkad.order.domain.repository.OrderRepository;
import com.bhukkad.order.domain.service.impl.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.bhukkad.order.api.RestaurantOwnerResolver;
import com.bhukkad.order.api.dto.response.OrderResponse;

/**
 * Owner / delivery-agent order-management surface (monolith parity for the
 * merchant and rider apps):
 *
 * <ul>
 *   <li>{@code GET  /api/v1/orders/restaurant/{rid}} — paged orders per restaurant</li>
 *   <li>{@code GET  /api/v1/orders/restaurant/{rid}/kitchen-queue} — active kitchen orders</li>
 *   <li>{@code GET  /api/v1/orders/restaurant/{rid}/pending} — awaiting acceptance</li>
 *   <li>{@code GET  /api/v1/orders/restaurant/{rid}/cursor} — cursor-paginated listing</li>
 *   <li>{@code PUT  /api/v1/orders/restaurant/{orderId}/accept} — owner accepts</li>
 *   <li>{@code PUT  /api/v1/orders/restaurant/{orderId}/ready} — owner readies</li>
 *   <li>{@code PUT  /api/v1/orders/restaurant/{orderId}/assign-delivery?agentId=}</li>
 *   <li>{@code GET  /api/v1/orders/delivery/my-deliveries} (+ {@code /cursor}) — agent deliveries</li>
 *   <li>{@code PUT  /api/v1/orders/delivery/{orderId}/picked-up|delivered} — rider transitions</li>
 * </ul>
 *
 * <p>Lifecycle writes authenticate via the JWT: owners may only touch their
 * own restaurants, agents only their own assignments; admins override.</p>
 */
@RestController
@RequiredArgsConstructor
public class OrderOpsController {

    private static final String SCOPE_ADMIN = "ADMIN";
    private static final String SCOPE_OWNER = "RESTAURANT_OWNER";
    private static final String SCOPE_AGENT = "DELIVERY_AGENT";

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final RestaurantOwnerResolver restaurantOwnerResolver;

    // ------------------------------------------------------------------
    // Owner: restaurant order views
    // ------------------------------------------------------------------

    @GetMapping("/api/v1/orders/restaurant/{restaurantId}")
    @Transactional(readOnly = true)
    public Map<String, Object> restaurantOrders(@AuthenticationPrincipal TokenPrincipal principal,
                                                @PathVariable Long restaurantId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        requireRestaurantOwnerOrAdmin(principal, restaurantId);
        List<Order> orders = orderRepository.findByRestaurantId(restaurantId);
        return paginate(orders, page, safeSize(size));
    }

    @GetMapping("/api/v1/orders/restaurant/{restaurantId}/kitchen-queue")
    @Transactional(readOnly = true)
    public List<OrderResponse> kitchenQueue(@AuthenticationPrincipal TokenPrincipal principal,
                                            @PathVariable Long restaurantId) {
        requireRestaurantOwnerOrAdmin(principal, restaurantId);
        return orderRepository.findByRestaurantId(restaurantId).stream()
                .filter(o -> isActiveKitchenStatus(o.getStatus()))
                .map(orderService::toResponseCompat)
                .toList();
    }

    @GetMapping("/api/v1/orders/restaurant/{restaurantId}/pending")
    @Transactional(readOnly = true)
    public List<OrderResponse> pendingOrders(@AuthenticationPrincipal TokenPrincipal principal,
                                             @PathVariable Long restaurantId) {
        requireRestaurantOwnerOrAdmin(principal, restaurantId);
        return orderRepository.findByRestaurantId(restaurantId).stream()
                .filter(o -> Order.STATUS_CREATED.equals(o.getStatus())
                        || Order.STATUS_PLACED.equals(o.getStatus()))
                .map(orderService::toResponseCompat)
                .toList();
    }

    @GetMapping("/api/v1/orders/restaurant/{restaurantId}/cursor")
    @Transactional(readOnly = true)
    public Map<String, Object> restaurantOrdersCursor(@AuthenticationPrincipal TokenPrincipal principal,
                                                      @PathVariable Long restaurantId,
                                                      @RequestParam(required = false) Long cursor,
                                                      @RequestParam(defaultValue = "10") int size) {
        requireRestaurantOwnerOrAdmin(principal, restaurantId);
        List<Order> orders = orderRepository.findByRestaurantId(restaurantId).stream()
                .filter(o -> cursor == null || o.getId() > cursor)
                .limit(safeSize(size))
                .toList();
        Long next = orders.size() == safeSize(size) ? orders.get(orders.size() - 1).getId() : null;
        return Map.of(
                "items", orders.stream().map(orderService::toResponseCompat).toList(),
                "nextCursor", next == null ? "" : String.valueOf(next),
                "hasNext", next != null);
    }

    // ------------------------------------------------------------------
    // Owner: lifecycle transitions (path carries the ORDER id, matching the
    // merchant app's monolith parity)
    // ------------------------------------------------------------------

    @PutMapping("/api/v1/orders/restaurant/{orderId}/accept")
    @Transactional
    public OrderResponse acceptOrder(@AuthenticationPrincipal TokenPrincipal principal,
                                     @PathVariable Long orderId) {
        Order order = requireOrder(orderId);
        requireRestaurantOwnerOrAdmin(principal, order.getRestaurantId());
        return orderService.transition(orderId, Order.STATUS_CONFIRMED, "ACCEPTED");
    }

    @PutMapping("/api/v1/orders/restaurant/{orderId}/ready")
    @Transactional
    public OrderResponse readyOrder(@AuthenticationPrincipal TokenPrincipal principal,
                                    @PathVariable Long orderId) {
        Order order = requireOrder(orderId);
        requireRestaurantOwnerOrAdmin(principal, order.getRestaurantId());
        return orderService.transition(orderId, Order.STATUS_READY_FOR_PICKUP, "READY");
    }

    @PutMapping("/api/v1/orders/restaurant/{orderId}/assign-delivery")
    @Transactional
    public OrderResponse assignDelivery(@AuthenticationPrincipal TokenPrincipal principal,
                                        @PathVariable Long orderId,
                                        @RequestParam Long agentId) {
        Order order = requireOrder(orderId);
        requireRestaurantOwnerOrAdmin(principal, order.getRestaurantId());
        return orderService.assignDeliveryAgent(orderId, agentId);
    }

    // ------------------------------------------------------------------
    // Delivery agent
    // ------------------------------------------------------------------

    @GetMapping("/api/v1/orders/delivery/my-deliveries")
    @Transactional(readOnly = true)
    public Map<String, Object> myDeliveries(@AuthenticationPrincipal TokenPrincipal principal,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        Long agentId = requireAgent(principal);
        List<Order> orders = orderRepository.findByDeliveryAgentId(agentId);
        return paginate(orders, page, safeSize(size));
    }

    @GetMapping("/api/v1/orders/delivery/my-deliveries/cursor")
    @Transactional(readOnly = true)
    public Map<String, Object> myDeliveriesCursor(@AuthenticationPrincipal TokenPrincipal principal,
                                                  @RequestParam(required = false) Long cursor,
                                                  @RequestParam(defaultValue = "10") int size) {
        Long agentId = requireAgent(principal);
        List<Order> orders = orderRepository.findByDeliveryAgentId(agentId).stream()
                .filter(o -> cursor == null || o.getId() > cursor)
                .limit(safeSize(size))
                .toList();
        Long next = orders.size() == safeSize(size) ? orders.get(orders.size() - 1).getId() : null;
        return Map.of(
                "items", orders.stream().map(orderService::toResponseCompat).toList(),
                "nextCursor", next == null ? "" : String.valueOf(next),
                "hasNext", next != null);
    }

    @PutMapping("/api/v1/orders/delivery/{orderId}/picked-up")
    @Transactional
    public OrderResponse pickedUp(@AuthenticationPrincipal TokenPrincipal principal,
                                  @PathVariable Long orderId) {
        Order order = requireOrder(orderId);
        requireAssignedAgentOrAdmin(principal, order);
        return orderService.transition(orderId, Order.STATUS_OUT_FOR_DELIVERY, "PICKED_UP");
    }

    @PutMapping("/api/v1/orders/delivery/{orderId}/delivered")
    @Transactional
    public OrderResponse delivered(@AuthenticationPrincipal TokenPrincipal principal,
                                   @PathVariable Long orderId) {
        Order order = requireOrder(orderId);
        requireAssignedAgentOrAdmin(principal, order);
        return orderService.transition(orderId, Order.STATUS_DELIVERED, "DELIVERED");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Order requireOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    }

    private static boolean isActiveKitchenStatus(String status) {
        return Order.STATUS_CONFIRMED.equals(status)
                || Order.STATUS_PREPARING.equals(status)
                || Order.STATUS_READY_FOR_PICKUP.equals(status);
    }

    private Map<String, Object> paginate(List<Order> orders, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(Math.min(size, 100), 1);
        int from = Math.min(safePage * safeSize, orders.size());
        int to = Math.min(from + safeSize, orders.size());
        List<Order> slice = orders.subList(from, to);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", slice.stream().map(orderService::toResponseCompat).toList());
        body.put("page", safePage);
        body.put("size", safeSize);
        body.put("totalElements", orders.size());
        body.put("hasNext", to < orders.size());
        return body;
    }

    private static int safeSize(int size) {
        return Math.max(Math.min(size, 100), 1);
    }

    private static boolean isAdmin(TokenPrincipal principal) {
        return principal != null && SCOPE_ADMIN.equalsIgnoreCase(String.valueOf(principal.scope()));
    }

    /**
     * Owner gate with object-level binding (audit HIGH-IDOR-3): the scope
     * check alone only proved the caller is <i>an</i> owner — the restaurant
     * ownership oracle must confirm THIS owner owns THIS restaurant. Admins
     * override; unknown restaurants and oracle outages fail CLOSED (denial,
     * never a pass).
     */
    private void requireRestaurantOwnerOrAdmin(TokenPrincipal principal, Long restaurantId) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated owner required");
        }
        if (isAdmin(principal)) {
            return;
        }
        if (!SCOPE_OWNER.equalsIgnoreCase(String.valueOf(principal.scope()))) {
            throw new AccessDeniedException("Owner access required");
        }
        Long ownerId = restaurantOwnerResolver.ownerIdOf(restaurantId);
        if (ownerId == null || !ownerId.equals(principal.userId())) {
            throw new AccessDeniedException("Not your restaurant");
        }
    }

    private static Long requireAgent(TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated delivery agent required");
        }
        if (!SCOPE_AGENT.equalsIgnoreCase(String.valueOf(principal.scope()))
                && !SCOPE_ADMIN.equalsIgnoreCase(String.valueOf(principal.scope()))) {
            throw new AccessDeniedException("Delivery agent access required");
        }
        return principal.userId();
    }

    private void requireAssignedAgentOrAdmin(TokenPrincipal principal, Order order) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated delivery agent required");
        }
        if (isAdmin(principal)) {
            return;
        }
        if (!principal.userId().equals(order.getDeliveryAgentId())) {
            throw new AccessDeniedException("Delivery not assigned to this agent");
        }
    }
}
