package com.bhukkad.order.api;

import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.service.CartService;
import com.bhukkad.order.service.OrderService;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Monolith-parity customer order surface ({@code /api/v1/orders/customer/*}).
 * The acting customer is ALWAYS the authenticated subject (IDOR-safe); body
 * customerId is ignored when it disagrees. Canonical nested surface:
 * {@code /api/v1/customers/{id}/orders} ({@link CustomerOrderController}).
 */
@RestController
@RequestMapping("/api/v1/orders/customer")
public class LegacyOrderCompatController {

    private static final String SCOPE_ADMIN = "ADMIN";

    private final OrderService orderService;
    private final CartService cartService;
    private final com.bhukkad.order.domain.OrderRepository orderRepository;
    private final com.bhukkad.order.client.RestaurantClient restaurantClient;
    private final com.bhukkad.order.service.OrderCreateJobService orderCreateJobService;
    private final com.bhukkad.order.service.AsyncOrderCreateService asyncOrderCreateService;

    public LegacyOrderCompatController(OrderService orderService, CartService cartService,
                                       com.bhukkad.order.domain.OrderRepository orderRepository,
                                       com.bhukkad.order.client.RestaurantClient restaurantClient,
                                       com.bhukkad.order.service.OrderCreateJobService orderCreateJobService,
                                       com.bhukkad.order.service.AsyncOrderCreateService asyncOrderCreateService) {
        this.orderService = orderService;
        this.cartService = cartService;
        this.orderRepository = orderRepository;
        this.restaurantClient = restaurantClient;
        this.orderCreateJobService = orderCreateJobService;
        this.asyncOrderCreateService = asyncOrderCreateService;
    }

    @PostMapping("/create")
    public OrderResponse create(@AuthenticationPrincipal TokenPrincipal principal,
                                @RequestBody CreateOrderRequest request) {
        Long customerId = subjectId(principal);
        if (request == null || request.restaurantId() == null) {
            // Empty/invalid order bodies must 400 (never NPE → 500).
            throw new com.bhukkad.common.error.BusinessException("restaurantId is required");
        }
        java.util.List<OrderItemRequest> items = request.items();
        boolean fromCart = false;
        if (items == null || items.isEmpty()) {
            // Monolith parity: an omitted item list checks out the customer's
            // ACTIVE CART (server-side snapshot — never client-trusted).
            items = cartService.getItems(customerId).stream()
                    .map(ci -> new OrderItemRequest(ci.getMenuItemId(), ci.getItemName(),
                            ci.getUnitPrice(), ci.getQuantity()))
                    .toList();
            fromCart = true;
        }
        if (items.isEmpty()) {
            throw new com.bhukkad.common.error.BusinessException(
                    "Order requires at least one item");
        }
        OrderResponse response = orderService.createOrder(
                new CreateOrderRequest(customerId, request.restaurantId(), items));
        if (fromCart) {
            cartService.clear(customerId);
        }
        return response;
    }

    /**
     * Async order create (mobile app batch checkout): {@code ?async=true}
     * snapshots the cart into a job and returns 202 with the poll URL while
     * {@link AsyncOrderCreateService} builds the order in the background.
     * The job poll endpoint lives at {@code /create/jobs/{jobId}}.
     */
    @PostMapping(value = "/create", params = "async=true")
    public org.springframework.http.ResponseEntity<Object> createAsync(
            @AuthenticationPrincipal TokenPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CreateOrderRequest request) {
        Long customerId = subjectId(principal);
        if (request == null || request.restaurantId() == null) {
            throw new com.bhukkad.common.error.BusinessException("restaurantId is required");
        }
        java.util.List<OrderItemRequest> items = request.items();
        if (items == null || items.isEmpty()) {
            items = cartService.getItems(customerId).stream()
                    .map(ci -> new OrderItemRequest(ci.getMenuItemId(), ci.getItemName(),
                            ci.getUnitPrice(), ci.getQuantity()))
                    .toList();
        }
        if (items.isEmpty()) {
            throw new com.bhukkad.common.error.BusinessException(
                    "Order requires at least one item");
        }
        String jobId = orderCreateJobService.createJob(idempotencyKey);
        CreateOrderRequest scoped =
                new CreateOrderRequest(customerId, request.restaurantId(), items);
        asyncOrderCreateService.processOrderCreate(jobId, scoped);
        // Cart is cleared optimistically: the async snapshot already holds the
        // line items, so a retry with the same key cannot double-add.
        cartService.clear(customerId);
        return org.springframework.http.ResponseEntity.accepted().body(
                java.util.Map.of("jobId", jobId,
                        "status", "PROCESSING",
                        "pollUrl", "/api/v1/orders/customer/create/jobs/" + jobId));
    }

    /** Async order-create job status (mobile batch checkout polling). */
    @GetMapping("/create/jobs/{jobId}")
    public Object orderCreateJob(@AuthenticationPrincipal TokenPrincipal principal,
                                 @PathVariable String jobId) {
        return orderCreateJobService.getJob(jobId);
    }

    @GetMapping("/my-orders")
    public List<OrderResponse> myOrders(@AuthenticationPrincipal TokenPrincipal principal) {
        return orderService.getOrdersForCustomer(subjectId(principal));
    }

    @GetMapping("/my-orders/cursor")
    public List<OrderResponse> myOrdersCursor(@AuthenticationPrincipal TokenPrincipal principal) {
        return orderService.getOrdersForCustomer(subjectId(principal));
    }

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(@AuthenticationPrincipal TokenPrincipal principal,
                                  @PathVariable Long orderId) {
        requireOwnerOrAdmin(principal, orderService.getOrder(orderId).customerId());
        return orderService.getOrder(orderId);
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal TokenPrincipal principal,
                                @PathVariable Long orderId) {
        requireOwnerOrAdmin(principal, orderService.getOrder(orderId).customerId());
        orderService.cancelOrder(orderId);
        return orderService.getOrder(orderId);
    }

    /**
     * PUT alias of the cancel endpoint (monolith parity — the merchant app
     * PUTs). A non-numeric id fails Long conversion and surfaces as 400 via
     * the shared MethodArgumentTypeMismatch handler.
     */
    @org.springframework.web.bind.annotation.PutMapping("/{orderId}/cancel")
    public OrderResponse cancelPut(@AuthenticationPrincipal TokenPrincipal principal,
                                   @PathVariable Long orderId) {
        return cancel(principal, orderId);
    }

    /**
     * Delivery tracking snapshot for the customer app (monolith parity):
     * returns the live order plus a simple status timeline. Shares the
     * canonical {@code order-track} bucket with OrderAdjunctController so
     * burst-throttling holds on either alias.
     */
    @GetMapping("/track/{orderId}")
    @com.bhukkad.common.ratelimit.RateLimited(bucket = "order-track", limit = 20, windowSeconds = 60)
    public java.util.Map<String, Object> track(@AuthenticationPrincipal TokenPrincipal principal,
                                               @PathVariable Long orderId) {
        requireOwnerOrAdmin(principal, orderService.getOrder(orderId).customerId());
        OrderResponse order = orderService.getOrder(orderId);
        java.util.List<String> timeline = orderService.timelineEvents(orderId);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", order.id());
        body.put("customerId", order.customerId());
        body.put("restaurantId", order.restaurantId());
        body.put("status", order.status());
        body.put("totalAmount", order.totalAmount());
        body.put("items", order.items());
        body.put("timeline", timeline);
        return body;
    }

    /**
     * Reorder: re-places a past order's items as a fresh order for the same
     * customer (customer-app "order it again" affordance).
     */
    @org.springframework.web.bind.annotation.PostMapping("/{orderId}/reorder")
    public OrderResponse reorder(@AuthenticationPrincipal TokenPrincipal principal,
                                 @PathVariable Long orderId) {
        Long customerId = subjectId(principal);
        return orderService.reorder(customerId, orderId);
    }

    // ------------------------------------------------------------------
    // Monolith-parity listing surfaces
    // ------------------------------------------------------------------

    /** Upcoming scheduled orders (kept literal so {@code /{orderId}} cannot shadow it). */
    @GetMapping("/scheduled-orders")
    public List<OrderResponse> scheduledOrders(@AuthenticationPrincipal TokenPrincipal principal) {
        return orderService.getOrdersForCustomer(subjectId(principal)).stream()
                .filter(o -> "SCHEDULED".equals(o.status()))
                .toList();
    }

    @GetMapping("/scheduled-orders/cursor")
    public List<OrderResponse> scheduledOrdersCursor(@AuthenticationPrincipal TokenPrincipal principal) {
        return scheduledOrders(principal);
    }

    /**
     * Batch status lookup. {@code ids} is a comma-separated list; ids the
     * caller does not own are silently dropped (IDOR-safe — the response
     * never confirms other customers' orders exist).
     */
    @GetMapping("/batch")
    public List<OrderResponse> batch(@AuthenticationPrincipal TokenPrincipal principal,
                                     @org.springframework.web.bind.annotation.RequestParam(required = false) String ids) {
        Long customerId = subjectId(principal);
        if (ids == null || ids.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> s.matches("\\d+"))
                .map(Long::valueOf)
                .map(orderRepository::findById)
                .flatMap(java.util.Optional::stream)
                .filter(o -> customerId.equals(o.getCustomerId()))
                .map(orderService::toResponseCompat)
                .toList();
    }

    /** CSV export of the customer's order history (DPDP data-portability). */
    @GetMapping("/export/orders")
    public org.springframework.http.ResponseEntity<String> exportOrders(
            @AuthenticationPrincipal TokenPrincipal principal) {
        Long customerId = subjectId(principal);
        StringBuilder csv = new StringBuilder("order_id,order_number,restaurant_id,status,total_amount,created_at\n");
        for (com.bhukkad.order.domain.Order order : orderRepository.findByCustomerId(customerId)) {
            csv.append(order.getId()).append(',')
                    .append(order.getOrderNumber() == null ? "" : order.getOrderNumber()).append(',')
                    .append(order.getRestaurantId()).append(',')
                    .append(order.getStatus()).append(',')
                    .append(order.getTotalAmount()).append(',')
                    .append(order.getCreatedAt() == null ? "" : order.getCreatedAt()).append('\n');
        }
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=orders.csv")
                .header("Content-Type", "text/csv")
                .body(csv.toString());
    }

    /** Batch checkout: one idempotent order per cart across N restaurants. */
    @PostMapping("/create-batch")
    public List<OrderResponse> createBatch(@AuthenticationPrincipal TokenPrincipal principal,
                                           @RequestHeader(value = "Idempotency-Key", required = false)
                                           String idempotencyKey,
                                           @RequestBody(required = false) CreateOrderRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new com.bhukkad.common.error.BusinessException("Idempotency-Key header is required");
        }
        Long customerId = subjectId(principal);
        var items = cartService.getItems(customerId);
        if (items.isEmpty()) {
            throw new com.bhukkad.common.error.BusinessException("Cart is empty");
        }
        // Group cart lines by restaurant; one order per restaurant.
        java.util.Map<Long, List<com.bhukkad.order.domain.CartItem>> byRestaurant =
                new java.util.LinkedHashMap<>();
        for (com.bhukkad.order.domain.CartItem item : items) {
            byRestaurant.computeIfAbsent(resolveRestaurantId(item), k -> new java.util.ArrayList<>())
                    .add(item);
        }
        List<OrderResponse> orders = new java.util.ArrayList<>();
        for (var entry : byRestaurant.entrySet()) {
            List<OrderItemRequest> lineItems = entry.getValue().stream()
                    .map(ci -> new OrderItemRequest(ci.getMenuItemId(), ci.getItemName(),
                            ci.getUnitPrice(), ci.getQuantity()))
                    .toList();
            orders.add(orderService.createOrder(
                    new CreateOrderRequest(customerId, entry.getKey(), lineItems)));
        }
        cartService.clear(customerId);
        return orders;
    }

    /** Cancels a future scheduled order (monolith parity for the planner UI). */
    @PutMapping("/scheduled-orders/{orderId}/cancel")
    public OrderResponse cancelScheduled(@AuthenticationPrincipal TokenPrincipal principal,
                                         @PathVariable Long orderId,
                                         @RequestParam(required = false) String reason) {
        Long customerId = subjectId(principal);
        com.bhukkad.order.domain.Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new com.bhukkad.common.error.ResourceNotFoundException(
                        "Scheduled order not found: " + orderId));
        if (!customerId.equals(order.getCustomerId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Cannot cancel another customer's scheduled order");
        }
        if (!"SCHEDULED".equals(order.getStatus()) && !"PLACED".equals(order.getStatus())) {
            throw new com.bhukkad.common.error.BusinessException(
                    "Scheduled order already dispatched (" + order.getStatus() + ")");
        }
        orderService.cancelOrder(orderId);
        return orderService.getOrder(orderId);
    }

    /** Aggregate order stats for the customer profile surface. */
    @GetMapping("/stats")
    public java.util.Map<String, Object> stats(@AuthenticationPrincipal TokenPrincipal principal) {
        Long customerId = subjectId(principal);
        var orders = orderRepository.findByCustomerId(customerId);
        long delivered = orders.stream()
                .filter(o -> "DELIVERED".equals(o.getStatus())).count();
        double totalSpend = orders.stream()
                .filter(o -> o.getTotalAmount() != null)
                .mapToDouble(o -> o.getTotalAmount().doubleValue())
                .sum();
        return java.util.Map.of(
                "totalOrders", orders.size(),
                "deliveredOrders", delivered,
                "cancelledOrders", orders.stream()
                        .filter(o -> "CANCELLED".equals(o.getStatus())).count(),
                "totalSpend", totalSpend);
    }

    private Long resolveRestaurantId(com.bhukkad.order.domain.CartItem item) {
        Long rid = restaurantClient.getMenuItemRestaurantId(item.getMenuItemId())
                .block(java.time.Duration.ofSeconds(5));
        if (rid == null) {
            throw new com.bhukkad.common.error.BusinessException(
                    "Cannot determine the restaurant for menu item " + item.getMenuItemId()
                            + " (item may have been deleted); remove it from the cart and retry");
        }
        return rid;
    }

    private static Long subjectId(TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated customer required");
        }
        return principal.userId();
    }

    private static void requireOwnerOrAdmin(TokenPrincipal principal, Long ownerId) {
        Long actor = subjectId(principal);
        boolean admin = SCOPE_ADMIN.equalsIgnoreCase(String.valueOf(principal.scope()));
        if (!admin && !actor.equals(ownerId)) {
            throw new AccessDeniedException("Cannot act on another customer's orders");
        }
    }
}
