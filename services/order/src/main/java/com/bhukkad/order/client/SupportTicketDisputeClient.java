package com.bhukkad.order.client;

import com.bhukkad.common.web.client.CircuitBreakerFilter;
import com.bhukkad.common.web.client.RetryFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Service-to-service client for the SupportTicket service (dispute operations).
 *
 * <p>Uses WebClient with built-in resilience: retry (3 attempts, 1s backoff),
 * circuit breaker (50% failure threshold, 10s open state), and timeout (3s).</p>
 */
@Component
public class SupportTicketDisputeClient {

    private final WebClient webClient;

    public SupportTicketDisputeClient(@Value("${app.services.supportticket.url}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .filter(new RetryFilter(3, Duration.ofSeconds(1)))
                .filter(new CircuitBreakerFilter("supportticket", CircuitBreakerFilter.DEFAULT_CONFIG))
                .build();
    }

    /** DTO matching supportticket's DisputeRequest */
    public static class DisputeRequest {
        private String type;
        private String customerEvidence;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getCustomerEvidence() {
            return customerEvidence;
        }

        public void setCustomerEvidence(String customerEvidence) {
            this.customerEvidence = customerEvidence;
        }
    }

    /** DTO matching supportticket's DisputeResolveRequest */
    public static class DisputeResolveRequest {
        private String resolution;
        private Double refundAmount;
        private String notes;

        public String getResolution() {
            return resolution;
        }

        public void setResolution(String resolution) {
            this.resolution = resolution;
        }

        public Double getRefundAmount() {
            return refundAmount;
        }

        public void setRefundAmount(Double refundAmount) {
            this.refundAmount = refundAmount;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }
    }

    /** DTO matching supportticket's DisputeResponse */
    public static class DisputeResponse {
        private Long id;
        private Long orderId;
        private String orderNumber;
        private String type;
        private String status;
        private String customerEvidence;
        private String riderEvidence;
        private String restaurantEvidence;
        private String resolutionNotes;
        private String resolution;
        private Double refundAmount;
        private Long resolvedBy;
        private String resolvedAt;
        private String createdAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public String getOrderNumber() {
            return orderNumber;
        }

        public void setOrderNumber(String orderNumber) {
            this.orderNumber = orderNumber;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getCustomerEvidence() {
            return customerEvidence;
        }

        public void setCustomerEvidence(String customerEvidence) {
            this.customerEvidence = customerEvidence;
        }

        public String getRiderEvidence() {
            return riderEvidence;
        }

        public void setRiderEvidence(String riderEvidence) {
            this.riderEvidence = riderEvidence;
        }

        public String getRestaurantEvidence() {
            return restaurantEvidence;
        }

        public void setRestaurantEvidence(String restaurantEvidence) {
            this.restaurantEvidence = restaurantEvidence;
        }

        public String getResolutionNotes() {
            return resolutionNotes;
        }

        public void setResolutionNotes(String resolutionNotes) {
            this.resolutionNotes = resolutionNotes;
        }

        public String getResolution() {
            return resolution;
        }

        public void setResolution(String resolution) {
            this.resolution = resolution;
        }

        public Double getRefundAmount() {
            return refundAmount;
        }

        public void setRefundAmount(Double refundAmount) {
            this.refundAmount = refundAmount;
        }

        public Long getResolvedBy() {
            return resolvedBy;
        }

        public void setResolvedBy(Long resolvedBy) {
            this.resolvedBy = resolvedBy;
        }

        public String getResolvedAt() {
            return resolvedAt;
        }

        public void setResolvedAt(String resolvedAt) {
            this.resolvedAt = resolvedAt;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }
    }

    /**
     * File a dispute for an order.
     *
     * @param orderId the order ID
     * @param request the dispute request
     * @return Mono of DisputeResponse
     */
    public Mono<DisputeResponse> fileDispute(Long orderId, DisputeRequest request) {
        return webClient.post()
                .uri("/api/v1/customers/orders/{orderId}/disputes", orderId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(DisputeResponse.class)
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Get disputes for the authenticated customer.
     *
     * @return Mono of list of DisputeResponse
     */
    public Mono<List<DisputeResponse>> getDisputesForCustomer() {
        return webClient.get()
                .uri("/api/v1/customers/disputes")
                .retrieve()
                .bodyToFlux(DisputeResponse.class)
                .collectList()
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(e -> Mono.just(List.of()));
    }

    /**
     * Get disputes for admin (all disputes).
     *
     * @return Mono of list of DisputeResponse
     */
    public Mono<List<DisputeResponse>> getDisputesForAdmin() {
        return webClient.get()
                .uri("/api/v1/admin/disputes")
                .retrieve()
                .bodyToFlux(DisputeResponse.class)
                .collectList()
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(e -> Mono.just(List.of()));
    }

    /**
     * Get a dispute by ID (admin endpoint).
     *
     * @param disputeId the dispute ID
     * @return Mono of DisputeResponse
     */
    public Mono<DisputeResponse> getDispute(Long disputeId) {
        return webClient.get()
                .uri("/api/v1/admin/disputes/{disputeId}", disputeId)
                .retrieve()
                .bodyToMono(DisputeResponse.class)
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Resolve a dispute (admin endpoint).
     *
     * @param disputeId the dispute ID
     * @param request the resolve request
     * @return Mono of DisputeResponse
     */
    public Mono<DisputeResponse> resolveDispute(Long disputeId, DisputeResolveRequest request) {
        return webClient.post()
                .uri("/api/v1/admin/disputes/{disputeId}/resolve", disputeId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(DisputeResponse.class)
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Trigger auto-resolution of disputes (admin endpoint).
     *
     * @return Mono of map with resolved count
     */
    public Mono<Map<String, Integer>> autoResolveDisputes() {
        return webClient.post()
                .uri("/api/v1/admin/disputes/auto-resolve")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Integer>>() {})
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(e -> Mono.just(Map.of("resolved", 0)));
    }
}
