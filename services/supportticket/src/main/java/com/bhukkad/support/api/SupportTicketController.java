package com.bhukkad.support.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.support.dto.request.SupportTicketRequest;
import com.bhukkad.support.dto.response.ApiResponse;
import com.bhukkad.support.dto.response.SupportTicketResponse;
import com.bhukkad.support.serviceImpl.SupportTicketServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Support ticket lifecycle endpoints.
 *
 * <p>Paths match the monolith's SupportTicketServiceClient contract. The
 * monolith resolves the authenticated caller and forwards its id in
 * {@code X-Customer-Id} (validated below) plus enforces admin role checks on
 * the admin routes before proxying.</p>
 */
@RestController
@RequestMapping("/api/v1/support")
@Tag(name = "Support", description = "Customer support tickets")
public class SupportTicketController {

    private final SupportTicketServiceImpl supportTicketService;

    public SupportTicketController(SupportTicketServiceImpl supportTicketService) {
        this.supportTicketService = supportTicketService;
    }

    @PostMapping("/tickets")
    @Operation(summary = "Create a support ticket for a customer")
    public ResponseEntity<ApiResponse<SupportTicketResponse>> create(
            @AuthenticationPrincipal com.bhukkad.common.security.TokenPrincipal principal,
            @Valid @RequestBody SupportTicketRequest request) {
        // Identity from the validated JWT: the spoofable X-Customer-Id header
        // previously let any caller file tickets as any customer.
        requireCustomerId(principal);
        return ResponseEntity.ok(ApiResponse.success("Support ticket created",
                supportTicketService.create(principal.userId(), request)));
    }

    @GetMapping("/tickets")
    @Operation(summary = "List a customer's support tickets, newest first")
    public ResponseEntity<ApiResponse<List<SupportTicketResponse>>> listCustomerTickets(
            @AuthenticationPrincipal com.bhukkad.common.security.TokenPrincipal principal) {
        requireCustomerId(principal);
        return ResponseEntity.ok(ApiResponse.success(
                supportTicketService.listCustomerTickets(principal.userId())));
    }

    @GetMapping("/admin/tickets")
    @Operation(summary = "List all support tickets for admin review")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<SupportTicketResponse>>> listAllForAdmin() {
        return ResponseEntity.ok(ApiResponse.success(supportTicketService.listAllForAdmin()));
    }

    @PutMapping("/admin/tickets/{ticketId}/status")
    @Operation(summary = "Update ticket status and resolution notes (admin)")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SupportTicketResponse>> adminUpdateStatus(
            @PathVariable @Positive Long ticketId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success(supportTicketService.adminUpdateStatus(
                ticketId, body.get("status"), body.get("resolutionNotes"))));
    }

    private static void requireCustomerId(com.bhukkad.common.security.TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new com.bhukkad.common.error.UnauthorizedException("Authenticated customer required");
        }
    }
}