package com.bhukkad.engagement.chat.ai;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.common.web.client.PlatformWebClientBuilderFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * Support ticket tool that calls the Admin Service's support ticket API.
 * Uses the platform WebClient with circuit breaker and mesh authentication.
 */
@Component
public class SupportTicketToolImpl implements SupportTicketTool {

    private static final String TARGET = "support-ticket";

    private final WebClient webClient;
    private final ObjectProvider<ServiceJwtAuthTokenProvider> tokenProvider;

    public SupportTicketToolImpl(
            @Value("${app.services.admin.url:http://admin:8080}") String adminBaseUrl,
            ObjectProvider<ServiceJwtAuthTokenProvider> tokenProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.tokenProvider = tokenProvider;
        this.webClient = PlatformWebClientBuilderFactory.forTarget(TARGET,
                        meterRegistryProvider.getIfAvailable())
                .responseTimeout(Duration.ofSeconds(5))
                .build()
                .mutate()
                .baseUrl(adminBaseUrl)
                .filter((request, next) -> {
                    String token = meshToken();
                    return next.exchange(token == null ? request
                            : ClientRequest.from(request).header("X-Service-Token", token).build());
                })
                .build();
    }

    private String meshToken() {
        ServiceJwtAuthTokenProvider provider = tokenProvider.getIfAvailable();
        return provider == null ? null : provider.serviceToken();
    }

    @Override
    public TicketResult createTicket(Long customerId,
                                     Long orderId,
                                     String subject,
                                     String description,
                                     String category,
                                     String priority,
                                     Map<String, Object> conversationContext) {

        if (customerId == null) {
            return new TicketResult(false, null, null, "REJECTED", "Customer ID required");
        }

        try {
            // Build request matching SupportTicketRequest DTO
            Map<String, Object> request = Map.of(
                    "customerId", customerId,
                    "orderId", orderId,
                    "subject", subject,
                    "description", description,
                    "category", category != null ? category : "chatbot-handoff",
                    "priority", priority != null ? priority : "MEDIUM"
            );

            // Add conversation context to description
            StringBuilder fullDescription = new StringBuilder(description);
            if (conversationContext != null && !conversationContext.isEmpty()) {
                fullDescription.append("\n\n--- Conversation Context ---\n");
                conversationContext.forEach((k, v) -> fullDescription.append(k).append(": ").append(v).append("\n"));
            }
            request = Map.of(
                    "customerId", customerId,
                    "orderId", orderId,
                    "subject", subject,
                    "description", fullDescription.toString(),
                    "category", category != null ? category : "chatbot-handoff",
                    "priority", priority != null ? priority : "MEDIUM"
            );

            SupportTicketResponse response = webClient.post()
                    .uri("/api/v1/support/tickets")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(SupportTicketResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .onErrorResume(e -> {
                        // Fallback: create a local ticket reference
                        return Mono.just(new SupportTicketResponse(
                                -1L, "TKT-LOCAL-" + System.currentTimeMillis(),
                                "LOCAL_FALLBACK", "Created locally due to service error: " + e.getMessage()
                        ));
                    })
                    .block();

            if (response != null && response.id() != null && response.id() > 0) {
                return new TicketResult(true, response.id().toString(), response.ticketNumber(),
                        response.status(), "Support ticket created successfully");
            } else {
                return new TicketResult(false, null, null, "FAILED", "Failed to create ticket");
            }

        } catch (Exception e) {
            return new TicketResult(false, null, null, "ERROR", "Error creating ticket: " + e.getMessage());
        }
    }

    /**
     * Response DTO matching admin service's SupportTicketResponse.
     */
    private record SupportTicketResponse(
            Long id,
            String ticketNumber,
            String status,
            String message
    ) {}
}