package com.bhukkad.order.api.controller;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.infrastructure.client.SupportTicketDisputeClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Dispute API delegated to SupportTicket service.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DisputeController {

    private final SupportTicketDisputeClient supportTicketClient;

    // ── Customer endpoints ───────────────────────────────────────────────────

    @PostMapping("/customers/orders/{orderId}/disputes")
    public Mono<ResponseEntity<SupportTicketDisputeClient.DisputeResponse>> fileDispute(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable Long orderId,
            @Valid @RequestBody SupportTicketDisputeClient.DisputeRequest request) {
        // Note: SupportTicket service extracts customerId from the token.
        return supportTicketClient.fileDispute(orderId, request)
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    @GetMapping("/customers/disputes")
    public Mono<ResponseEntity<List<SupportTicketDisputeClient.DisputeResponse>>> myDisputes(@AuthenticationPrincipal TokenPrincipal principal) {
        return supportTicketClient.getDisputesForCustomer()
                .map(ResponseEntity::ok);
    }

    // ── Admin endpoints ──────────────────────────────────────────────────────

    @GetMapping("/admin/disputes")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<List<SupportTicketDisputeClient.DisputeResponse>>> listDisputes() {
        return supportTicketClient.getDisputesForAdmin()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/admin/disputes/{disputeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<SupportTicketDisputeClient.DisputeResponse>> getDispute(@PathVariable Long disputeId) {
        return supportTicketClient.getDispute(disputeId)
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    @PostMapping("/admin/disputes/{disputeId}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<SupportTicketDisputeClient.DisputeResponse>> resolveDispute(
            @PathVariable Long disputeId,
            @Valid @RequestBody SupportTicketDisputeClient.DisputeResolveRequest request) {
        return supportTicketClient.resolveDispute(disputeId, request)
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    @PostMapping("/admin/disputes/auto-resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<Map<String, Integer>>> autoResolve() {
        return supportTicketClient.autoResolveDisputes()
                .map(ResponseEntity::ok);
    }
}
