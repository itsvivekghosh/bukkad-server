package com.bhukkad.social.api.controller;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.ratelimit.RateLimited;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.social.api.dto.request.OrderFromPostRequest;
import com.bhukkad.order.api.dto.response.OrderResponse;
import com.bhukkad.social.api.dto.response.PostSummary;
import com.bhukkad.social.domain.service.SocialPostService;
import com.bhukkad.social.domain.service.impl.OrderFromPostIdempotencyService;
import com.bhukkad.social.infrastructure.client.OrderServiceClient;
import com.bhukkad.social.infrastructure.client.RestaurantClient;
import com.bhukkad.social.infrastructure.messaging.OrderFromPostEventPublisher;
import com.bhukkad.social.observability.PerformanceMetrics;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Controller for creating orders from social posts.
 * Implements the "order-from-post" functionality for social commerce.
 *
 * <p>Uses parallel validation for post and menu items to minimize latency.
 * Target: < 200ms p95 at 10K TPS.</p>
 */
@RestController
@RequiredArgsConstructor
public class OrderFromPostController {

    private static final int VALIDATION_TIMEOUT_MS = 500;  // 500ms timeout for post lookup

    private final SocialPostService postService;
    private final RestaurantClient restaurantClient;
    private final OrderServiceClient orderServiceClient;
    private final OrderFromPostEventPublisher orderFromPostEventPublisher;
    private final OrderFromPostIdempotencyService idempotencyService;
    private final PerformanceMetrics performanceMetrics;

    /**
     * Create an order from a social post with parallel validation.
     *
     * @param postId the ID of the social post to order from
     * @param idempotencyKey the idempotency key for safe retries
     * @param request the order request containing menu items and quantities
     * @param principal the authenticated user making the request
     * @return the created order details
     */
    @PostMapping("/api/v1/social/posts/{postId}/order")
    @RateLimited(bucket = "social-order", limit = 50, windowSeconds = 60)
    public ResponseEntity<OrderResponse> createOrderFromPost(
            @PathVariable Long postId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderFromPostRequest request,
            @AuthenticationPrincipal TokenPrincipal principal) {

        performanceMetrics.recordOrderRequest();
        Timer.Sample timer = performanceMetrics.startOrderTimer();

        Long userId = principal == null ? null : principal.userId();

        try {
            // Parallel validation: fetch post and validate restaurant existence simultaneously
            CompletableFuture<PostSummary> postFuture = CompletableFuture.supplyAsync(() ->
                    postService.getPost(postId));

            // We'll need to validate menu items after getting the post, so we start with post validation
            PostSummary post;
            try {
                post = postFuture.get(VALIDATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new ResourceNotFoundException("Post not found or validation timeout");
            }

            // Validate that the post is associated with a restaurant
            Long restaurantId = post.restaurantId();
            if (restaurantId == null) {
                throw new BusinessException("Post is not associated with a restaurant");
            }

            // Validate that the restaurant exists
            Map<String, Object> restaurant = restaurantClient.getRestaurant(restaurantId);
            if (restaurant.isEmpty()) {
                throw new BusinessException("Restaurant not found");
            }

            // Parallel validation of all menu items
            List<OrderFromPostRequest.OrderItemRequest> items = request.items();
            List<CompletableFuture<Void>> validationFutures = items.stream()
                    .map(item -> CompletableFuture.runAsync(() -> {
                        Long menuItemId = item.menuItemId();
                        Map<String, Object> menuItem = restaurantClient.getMenuItem(menuItemId);

                        if (menuItem.isEmpty()) {
                            throw new BusinessException("Menu item not found: " + menuItemId);
                        }

                        Long itemRestaurantId = ((Number) menuItem.getOrDefault("restaurantId", 0L)).longValue();
                        if (!itemRestaurantId.equals(restaurantId)) {
                            throw new BusinessException("Menu item does not belong to the restaurant in the post");
                        }
                    }))
                    .toList();

            // Wait for all validations to complete with timeout
            try {
                CompletableFuture.allOf(validationFutures.toArray(new CompletableFuture[0]))
                        .get(VALIDATION_TIMEOUT_MS * items.size(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new BusinessException("Menu item validation failed: " + e.getMessage());
            }

            // Handle idempotency
            OrderFromPostIdempotencyService.Claim claim;
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                claim = idempotencyService.claim(userId, postId, idempotencyKey, request);
                if (!claim.fresh()) {
                    // Return cached response
                    performanceMetrics.recordOrderLatency(timer);
                    return ResponseEntity.status(claim.httpStatus())
                            .body(claim.replay());
                }
            } else {
                // No idempotency key - behave as if no key (run the operation)
                claim = OrderFromPostIdempotencyService.Claim.ofFresh();
            }

            try {
                // Convert social order request to order service request
                com.bhukkad.order.api.dto.request.CreateOrderRequest orderRequest =
                        new com.bhukkad.order.api.dto.request.CreateOrderRequest(
                                userId,
                                restaurantId,
                                convertToOrderItemRequests(request.items())
                        );

                // Create the order using the order service
                OrderResponse orderResponse = orderServiceClient.createOrderFromPost(orderRequest);

                // Publish Kafka event for post-to-order conversion
                orderFromPostEventPublisher.publishOrderFromPostEvent(
                        postId, userId, restaurantId, orderResponse.id(), orderResponse.totalAmount(), request.items());

                // Store successful response for idempotency replay
                if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                    idempotencyService.complete(userId, postId, idempotencyKey, request,
                            200, orderResponse);
                }

                // Return the created order
                URI location = URI.create("/api/v1/social/posts/" + postId + "/order/" + orderResponse.id());
                performanceMetrics.recordOrderLatency(timer);
                return ResponseEntity.created(location).body(orderResponse);

            } catch (Exception ex) {
                // Mark idempotency claim as failed if we had one
                if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                    idempotencyService.markFailed(userId, postId, idempotencyKey);
                }
                throw ex;
            }
        } catch (Exception ex) {
            performanceMetrics.recordOrderLatency(timer);
            throw ex;
        }
    }
    /**
     * Convert social module order item requests to order service requests.
     *
     * <p>The order service expects OrderItemRequest with name and unitPrice,
     * but these are ignored server-side for security. We pass placeholder
     * values as the social module doesn't have pricing/name information.</p>
     */
    private List<com.bhukkad.order.api.dto.request.OrderItemRequest> convertToOrderItemRequests(
            List<OrderFromPostRequest.OrderItemRequest> socialItems) {
        return socialItems.stream()
                .map(socialItem -> new com.bhukkad.order.api.dto.request.OrderItemRequest(
                        socialItem.menuItemId(),
                        socialItem.name(),  // Will be ignored server-side
                        socialItem.unitPrice(),  // Will be ignored server-side
                        socialItem.quantity()
                ))
                .toList();
    }
}