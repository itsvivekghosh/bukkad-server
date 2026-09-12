package com.bhukkad.order.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.common.web.client.PlatformWebClientBuilderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Service-to-service client for the SupportTicket service (dispute operations).
 *
 * <p>Runs on the platform WebClient factory (audit G-13/P-05): shared bounded
 * pool, platform timeouts, retry (3 attempts, 1s backoff on transient
 * idempotent failures), the {@code supportticket} circuit breaker
 * (50% failure threshold, 10s open state) and metrics; the service base URL
 * and mesh-token stamping are applied on the copied builder.
 * Every call carries the mesh service token on {@code X-Service-Token} —
 * supportticket authorizes dispute surfaces behind role guards, so tokenless
 * mesh legs used to be rejected with 401 and silently swallowed into empty
 * responses (the admin dispute console appeared to return no data).</p>
 */
@Component
public class SupportTicketDisputeClient {

    /** Breaker/metric target name — unchanged so breaker state survives the migration. */
    static final String TARGET = "supportticket";

    private final WebClient webClient;
    private final ObjectProvider<ServiceJwtAuthTokenProvider> authTokenProvider;

    public SupportTicketDisputeClient(
            @Value("${app.services.supportticket.url}") String baseUrl,
            ObjectProvider<ServiceJwtAuthTokenProvider> authTokenProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.authTokenProvider = authTokenProvider;
        this.webClient = PlatformWebClientBuilderFactory
                .forTarget("supportticket",
                        meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable())
                .build()
                .mutate()
                .baseUrl(baseUrl)
                // Mesh auth: stamp X-Service-Token when service auth is
                // enabled; supportticket rejects tokenless dispute calls.
                .filter((request, next) -> {
                    String token = meshToken();
                    return next.exchange(token == null ? request
                            : ClientRequest.from(request).header("X-Service-Token", token).build());
                })
                .build();
    }

    /** Mesh token when service auth is enabled; absent otherwise (dev). */
    private String meshToken() {
        ServiceJwtAuthTokenProvider provider = authTokenProvider.getIfAvailable();
        return provider == null ? null : provider.serviceToken();
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
