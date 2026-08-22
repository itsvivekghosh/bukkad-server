package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;

import com.bhukkad.delivery.DeliveryProofService;
import com.bhukkad.dto.request.BatchOrderRequest;
import com.bhukkad.dto.request.DeliveryProofPhotoUploadRequest;
import com.bhukkad.dto.request.DeliveryProofVerifyRequest;
import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.DeliveryProofPhotoUploadResponse;
import com.bhukkad.dto.response.DeliveryProofResponse;
import com.bhukkad.dto.response.BatchOrderResponse;
import com.bhukkad.dto.response.CursorPagedResponse;
import com.bhukkad.dto.response.OrderCreateJobResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.dto.response.OrderSummaryResponse;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.fraud.FraudDetectionService;
import com.bhukkad.fraud.FraudEventTypes;
import com.bhukkad.ratelimit.RateLimited;
import com.bhukkad.dto.response.ReorderResponse;
import com.bhukkad.order.AsyncOrderCreateService;
import com.bhukkad.order.OrderCreateJobService;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CartService;
import com.bhukkad.service.OrderService;
import com.bhukkad.util.PaginationUtils;
import com.bhukkad.web.FieldProjection;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;
import java.time.LocalDate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/orders")
@RequiredArgsConstructor
@Tag(name = "Order", description = "REST endpoints for Order")
public class OrderController {

    private final OrderService orderService;
    private final CartService cartService;
    private final AsyncOrderCreateService asyncOrderCreateService;
    private final OrderCreateJobService orderCreateJobService;
    private final FraudDetectionService fraudDetectionService;
    private final SecurityUtils securityUtils;
    private final DeliveryProofService deliveryProofService;

    /**
     * Places an order, either synchronously or as a background job.
     *
     * <p>The abuse check is deliberately the <strong>first</strong> statement, ahead of
     * both branches, for three reasons:
     * <ul>
     *   <li>The async branch hands work to an executor thread where
     *       {@code RequestContextHolder} is empty, so the client IP and device
     *       fingerprint are only observable here on the request thread.</li>
     *   <li>{@code OrderServiceImpl.createOrder} short-circuits to a cached response
     *       when the {@code Idempotency-Key} has already been seen, so a check placed
     *       inside the service would be silently skipped on replays.</li>
     *   <li>A block must not consume a job id or an idempotency slot; failing before
     *       {@code createJob} keeps the caller free to retry cleanly after
     *       {@code Retry-After} elapses.</li>
     * </ul>
     *
     * <p>Unlike the auth endpoints, the customer id is known here and is recorded on
     * the fraud event, which gives the abuse review queue an account to act on rather
     * than just a network address.
     *
     * @throws com.bhukkad.exception.FraudBlockedException as 429 when the source has
     *         exceeded the {@code order-create} threshold within the detection window
     */
    // Customer endpoints
    @PostMapping("/customer/create")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Create order")
    public ResponseEntity<ApiResponse<?>> createOrder(
            @Valid @RequestBody OrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(defaultValue = "false") boolean async) {
        fraudDetectionService.checkAndBlock(securityUtils.getCurrentUserId(), FraudEventTypes.ORDER_CREATE);
        if (async) {
            String jobId = orderCreateJobService.createJob(idempotencyKey);
            asyncOrderCreateService.processOrderCreate(jobId, request, idempotencyKey);
            OrderCreateJobResponse job = orderCreateJobService.getJob(jobId);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success("Order accepted for processing", job));
        }
        OrderResponse order = orderService.createOrder(request, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Order placed successfully", order));
    }

    @GetMapping("/customer/scheduled-orders")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Get my scheduled orders")
    public ResponseEntity<ApiResponse<PagedResponse<OrderSummaryResponse>>> getMyScheduledOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PagedResponse<OrderSummaryResponse> orders = orderService.getCustomerScheduledOrders(page, size);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/customer/scheduled-orders/cursor")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Get my scheduled orders by cursor")
    public ResponseEntity<ApiResponse<CursorPagedResponse<OrderSummaryResponse>>> getMyScheduledOrdersByCursor(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        CursorPagedResponse<OrderSummaryResponse> orders =
                orderService.getCustomerScheduledOrdersByCursor(cursor, size);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @PutMapping("/customer/scheduled-orders/{orderId}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Cancel scheduled order")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelScheduledOrder(
            @PathVariable Long orderId,
            @RequestParam String reason) {
        OrderResponse order = orderService.cancelScheduledOrder(orderId, reason);
        return ResponseEntity.ok(ApiResponse.success("Scheduled order cancelled successfully", order));
    }

    @PostMapping("/customer/create-batch")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Create batch orders")
    public ResponseEntity<ApiResponse<BatchOrderResponse>> createBatchOrders(
            @Valid @RequestBody BatchOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        BatchOrderResponse response = orderService.createBatchOrders(request, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Batch orders processed", response));
    }

    @GetMapping("/customer/create/jobs/{jobId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<OrderCreateJobResponse>> getCreateOrderJob(@PathVariable String jobId) {
        return ResponseEntity.ok(ApiResponse.success(orderCreateJobService.getJob(jobId)));
    }

    @GetMapping("/customer/my-orders")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Get my orders")
    public ResponseEntity<ApiResponse<PagedResponse<OrderSummaryResponse>>> getMyOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PagedResponse<OrderSummaryResponse> orders = orderService.getCustomerOrders(page, size);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/customer/my-orders/cursor")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Get my orders by cursor")
    public ResponseEntity<ApiResponse<CursorPagedResponse<OrderSummaryResponse>>> getMyOrdersByCursor(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        CursorPagedResponse<OrderSummaryResponse> orders =
                orderService.getCustomerOrdersByCursor(cursor, size);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/customer/{orderId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Get order by id")
    public ResponseEntity<MappingJacksonValue> getOrderById(
            @PathVariable Long orderId,
            @RequestParam(required = false) String fields) {
        OrderResponse order = orderService.getOrderById(orderId);
        return ResponseEntity.ok(FieldProjection.project(ApiResponse.success(order), fields));
    }

    /**
     * Returns the live tracking view of one of the caller's own orders, enriched with
     * {@code liveEtaMinutes} / {@code liveEtaAt} recalculated from order status and rider location.
     *
     * <p>Two URI forms are accepted so that the historically documented suffix form keeps working:
     * <ul>
     *   <li>{@code GET /api/v1/orders/customer/track/{orderId}} (canonical)</li>
     *   <li>{@code GET /api/v1/orders/customer/{orderId}/track} (documented alias)</li>
     * </ul>
     * Previously only the canonical form was mapped, so the documented alias fell through to
     * Spring MVC's {@code /error} forward and surfaced as {@code 403} instead of the real status.
     *
     * <p>Ownership is enforced in the service layer, and the tracking cache entry is scoped to the
     * requesting customer, so one customer can never read another customer's order.
     *
     * @param orderId identifier of the order to track; must belong to the authenticated customer
     * @return the order with live ETA fields populated
     */
    @GetMapping({"/customer/track/{orderId}", "/customer/{orderId}/track"})
    @PreAuthorize("hasRole('CUSTOMER')")
    @RateLimited("order-track")
    @Operation(summary = "Track order")
    public ResponseEntity<MappingJacksonValue> trackOrder(
            @PathVariable Long orderId,
            @RequestParam(required = false) String fields) {
        OrderResponse order = orderService.trackOrder(orderId);
        return ResponseEntity.ok(FieldProjection.project(ApiResponse.success(order), fields));
    }

    @PostMapping("/customer/{orderId}/reorder")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Reorder")
    public ResponseEntity<ApiResponse<ReorderResponse>> reorder(
            @PathVariable Long orderId) {
        ReorderResponse response = cartService.reorderFromOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success("Items added to cart", response));
    }

    private String exportOrdersToCsv(List<OrderSummaryResponse> orders) {
        StringBuilder sb = new StringBuilder();
        sb.append("Order Number,Customer,Restaurant,Status,Total,Date\n");
        for (OrderSummaryResponse order : orders) {
            sb.append(order.getOrderNumber()).append(",");
            sb.append(order.getCustomerName()).append(",");
            sb.append(order.getRestaurantName()).append(",");
            sb.append(order.getStatus()).append(",");
            sb.append(order.getTotalAmount()).append(",");
            sb.append(order.getCreatedAt().toString()).append("\n");
        }
        return sb.toString();
    }

    @GetMapping("/customer/export/orders")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Export order history")
    public ResponseEntity<byte[]> exportOrderHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long customerId = securityUtils.getCurrentUserId();
        PagedResponse<OrderSummaryResponse> pagedOrders = orderService.getCustomerOrders(page, size);
        String csv = exportOrdersToCsv(pagedOrders.getItems());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDispositionFormData("attachment", "bhukkad-orders-" + LocalDate.now() + ".csv");
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        return ResponseEntity.ok()
                .headers(headers)
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @PutMapping("/customer/{orderId}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Cancel order")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @PathVariable Long orderId,
            @RequestParam String reason) {
        OrderResponse order = orderService.cancelOrder(orderId, reason);
        return ResponseEntity.ok(ApiResponse.success("Order cancelled successfully", order));
    }

    // Restaurant / cloud kitchen endpoints
    @GetMapping("/restaurant/{restaurantId}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Get restaurant orders")
    public ResponseEntity<ApiResponse<PagedResponse<OrderSummaryResponse>>> getRestaurantOrders(
            @PathVariable Long restaurantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PagedResponse<OrderSummaryResponse> orders = orderService.getRestaurantOrders(restaurantId, page, size);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/restaurant/{restaurantId}/cursor")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Get restaurant orders by cursor")
    public ResponseEntity<ApiResponse<CursorPagedResponse<OrderSummaryResponse>>> getRestaurantOrdersByCursor(
            @PathVariable Long restaurantId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        CursorPagedResponse<OrderSummaryResponse> orders =
                orderService.getRestaurantOrdersByCursor(restaurantId, cursor, size);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/restaurant/{restaurantId}/pending")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Get pending orders")
    public ResponseEntity<ApiResponse<List<OrderSummaryResponse>>> getPendingOrders(
            @PathVariable Long restaurantId,
            @RequestParam(defaultValue = "" + PaginationUtils.KITCHEN_QUEUE_DEFAULT_LIMIT) int limit) {
        List<OrderSummaryResponse> orders = orderService.getPendingOrdersForRestaurant(restaurantId, limit);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/restaurant/{restaurantId}/kitchen-queue")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @RateLimited("kitchen-queue")
    @Operation(summary = "Get kitchen queue")
    public ResponseEntity<ApiResponse<List<OrderSummaryResponse>>> getKitchenQueue(
            @PathVariable Long restaurantId,
            @RequestParam(defaultValue = "" + PaginationUtils.KITCHEN_QUEUE_DEFAULT_LIMIT) int limit) {
        List<OrderSummaryResponse> orders = orderService.getKitchenActiveOrders(restaurantId, limit);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @PutMapping("/restaurant/{orderId}/accept")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<OrderResponse>> acceptOrder(@PathVariable Long orderId) {
        OrderResponse order = orderService.acceptOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success("Order accepted", order));
    }

    @PutMapping("/restaurant/{orderId}/ready")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<OrderResponse>> markOrderReady(@PathVariable Long orderId) {
        OrderResponse order = orderService.markOrderReady(orderId);
        return ResponseEntity.ok(ApiResponse.success("Order marked as ready", order));
    }

    @PutMapping("/restaurant/{orderId}/assign-delivery")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Assign delivery agent")
    public ResponseEntity<ApiResponse<OrderResponse>> assignDeliveryAgent(
            @PathVariable Long orderId,
            @RequestParam Long agentId) {
        OrderResponse order = orderService.assignDeliveryAgent(orderId, agentId);
        return ResponseEntity.ok(ApiResponse.success("Delivery agent assigned", order));
    }

    // Delivery agent endpoints
    @GetMapping("/delivery/my-deliveries")
    @PreAuthorize("hasRole('DELIVERY_AGENT')")
    @Operation(summary = "Get my deliveries")
    public ResponseEntity<ApiResponse<PagedResponse<OrderSummaryResponse>>> getMyDeliveries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PagedResponse<OrderSummaryResponse> orders = orderService.getDeliveryAgentOrders(null, page, size);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/delivery/my-deliveries/cursor")
    @PreAuthorize("hasRole('DELIVERY_AGENT')")
    @Operation(summary = "Get my deliveries by cursor")
    public ResponseEntity<ApiResponse<CursorPagedResponse<OrderSummaryResponse>>> getMyDeliveriesByCursor(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        CursorPagedResponse<OrderSummaryResponse> orders =
                orderService.getDeliveryAgentOrdersByCursor(null, cursor, size);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @PutMapping("/delivery/{orderId}/picked-up")
    @PreAuthorize("hasRole('DELIVERY_AGENT')")
    public ResponseEntity<ApiResponse<OrderResponse>> markOrderPickedUp(@PathVariable Long orderId) {
        OrderResponse order = orderService.updateDeliveryStatus(orderId, Order.OrderStatus.OUT_FOR_DELIVERY);
        return ResponseEntity.ok(ApiResponse.success("Order picked up", order));
    }

    @PutMapping("/delivery/{orderId}/delivered")
    @PreAuthorize("hasRole('DELIVERY_AGENT')")
    public ResponseEntity<ApiResponse<OrderResponse>> markOrderDelivered(@PathVariable Long orderId) {
        OrderResponse order = orderService.markOrderDelivered(orderId);
        return ResponseEntity.ok(ApiResponse.success("Order delivered successfully", order));
    }

    // ------------------------------------------------------------------
    // Delivery proof (OTP + photo) — rider handover evidence
    // ------------------------------------------------------------------
    // These four endpoints exist because "delivered" was previously a single
    // unverified button press by the rider. The proof flow records who received the
    // order: the customer reads a 6-digit code out loud (or the rider photographs the
    // handover), and only a verified proof lets the delivered transition through when
    // app.delivery.proof.enforced is on.
    //
    // Every endpoint is rider-only AND re-checks that this specific order is assigned
    // to the caller inside DeliveryProofService — hasRole('DELIVERY_AGENT') alone only
    // proves the caller is *a* rider, not *this* order's rider.

    /**
     * Issues (or re-issues) the handover OTP and texts it to the customer.
     *
     * <p>Safe to call again: the same proof row is reused and a fresh code replaces the
     * old one, subject to the resend cooldown. Returns 400 once the proof is already
     * verified or the cooldown has not elapsed. The code itself is never in the
     * response — only the customer's SMS carries it.
     */
    @PostMapping("/delivery/{orderId}/proof/otp")
    @PreAuthorize("hasRole('DELIVERY_AGENT')")
    public ResponseEntity<ApiResponse<DeliveryProofResponse>> issueDeliveryProofOtp(@PathVariable Long orderId) {
        DeliveryProofResponse proof = deliveryProofService.issueOtp(orderId);
        return ResponseEntity.ok(ApiResponse.success("Delivery OTP sent to customer", proof));
    }

    /**
     * Verifies the code the customer read out, optionally attaching a photo and
     * recipient details.
     *
     * <p>A wrong code returns 400 and permanently consumes one attempt, so this cannot
     * be brute-forced. Re-verifying an already verified order returns the existing
     * state rather than an error, because the rider app retries on flaky networks.
     */
    @PostMapping("/delivery/{orderId}/proof/verify")
    @PreAuthorize("hasRole('DELIVERY_AGENT')")
    @Operation(summary = "Verify delivery proof")
    public ResponseEntity<ApiResponse<DeliveryProofResponse>> verifyDeliveryProof(
            @PathVariable Long orderId,
            @Valid @RequestBody DeliveryProofVerifyRequest request) {
        DeliveryProofResponse proof = deliveryProofService.verify(orderId, request);
        return ResponseEntity.ok(ApiResponse.success("Delivery proof verified", proof));
    }

    /**
     * Mints a presigned URL for the handover photo.
     *
     * <p>Bytes go straight from the rider's phone to object storage; this server never
     * buffers the image. Nothing is persisted here — the photo joins the proof only
     * when its key is submitted on the verify call.
     */
    @PostMapping("/delivery/{orderId}/proof/photo-url")
    @PreAuthorize("hasRole('DELIVERY_AGENT')")
    @Operation(summary = "Create delivery proof photo url")
    public ResponseEntity<ApiResponse<DeliveryProofPhotoUploadResponse>> createDeliveryProofPhotoUrl(
            @PathVariable Long orderId,
            @Valid @RequestBody DeliveryProofPhotoUploadRequest request) {
        DeliveryProofService.PhotoUpload upload =
                deliveryProofService.createPhotoUploadUrl(orderId, request.getContentType());
        DeliveryProofPhotoUploadResponse response = DeliveryProofPhotoUploadResponse.builder()
                .uploadUrl(upload.uploadUrl())
                .photoKey(upload.photoKey())
                .build();
        return ResponseEntity.ok(ApiResponse.success("Upload URL generated", response));
    }

    /**
     * Reads the current proof state so the rider app can resume after a restart —
     * whether a code is outstanding, how many attempts remain, and whether the
     * delivered transition will be allowed.
     */
    @GetMapping("/delivery/{orderId}/proof")
    @PreAuthorize("hasRole('DELIVERY_AGENT')")
    public ResponseEntity<ApiResponse<DeliveryProofResponse>> getDeliveryProof(@PathVariable Long orderId) {
        DeliveryProofResponse proof = deliveryProofService.getForAgent(orderId);
        return ResponseEntity.ok(ApiResponse.success(proof));
    }

    // Common endpoint
    @GetMapping("/number/{orderNumber}")
    @Operation(summary = "Get order by number")
    public ResponseEntity<MappingJacksonValue> getOrderByNumber(
            @PathVariable String orderNumber,
            @RequestParam(required = false) String fields) {
        OrderResponse order = orderService.getOrderByNumber(orderNumber);
        return ResponseEntity.ok(FieldProjection.project(ApiResponse.success(order), fields));
    }

    /**
     * Batch read — returns a map keyed by order id for any subset of the
     * caller's own orders in a single round-trip. Caps at
     * {@link com.bhukkad.util.PaginationUtils#MAX_PAGE_SIZE} ids per request to
     * bound server memory and DB pressure; callers that need more should page
     * via the cursor endpoints instead.
     *
     * <p>Ids the caller does not own (or that do not exist) are silently
     * dropped — see {@code OrderServiceImpl.getOrdersByIds} for the rationale.
     */
    @GetMapping("/customer/batch")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Get orders by ids")
    public ResponseEntity<ApiResponse<java.util.Map<Long, OrderResponse>>> getOrdersByIds(
            @RequestParam("ids") java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(java.util.Collections.emptyMap()));
        }
        // Bound the request — capped at MAX_PAGE_SIZE so a malicious caller
        // can't issue ?ids=1,2,3,...,100000 and force a 100k-row IN-list.
        if (ids.size() > com.bhukkad.util.PaginationUtils.MAX_PAGE_SIZE) {
            throw new com.bhukkad.exception.BusinessException(
                    "Too many ids in batch request; max "
                            + com.bhukkad.util.PaginationUtils.MAX_PAGE_SIZE);
        }
        return ResponseEntity.ok(ApiResponse.success(orderService.getOrdersByIds(ids)));
    }
}
