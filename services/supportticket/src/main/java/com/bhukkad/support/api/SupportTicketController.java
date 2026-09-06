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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
            @RequestHeader(value = "X-Customer-Id", required = false) String customerIdHeader,
            @Valid @RequestBody SupportTicketRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Support ticket created",
                supportTicketService.create(parseCustomerId(customerIdHeader), request)));
    }

    @GetMapping("/tickets")
    @Operation(summary = "List a customer's support tickets, newest first")
    public ResponseEntity<ApiResponse<List<SupportTicketResponse>>> listCustomerTickets(
            @RequestHeader(value = "X-Customer-Id", required = false) String customerIdHeader) {
        return ResponseEntity.ok(ApiResponse.success(
                supportTicketService.listCustomerTickets(parseCustomerId(customerIdHeader))));
    }

    @GetMapping("/admin/tickets")
    @Operation(summary = "List all support tickets for admin review")
    public ResponseEntity<ApiResponse<List<SupportTicketResponse>>> listAllForAdmin() {
        return ResponseEntity.ok(ApiResponse.success(supportTicketService.listAllForAdmin()));
    }

    @PutMapping("/admin/tickets/{ticketId}/status")
    @Operation(summary = "Update ticket status and resolution notes (admin)")
    public ResponseEntity<ApiResponse<SupportTicketResponse>> adminUpdateStatus(
            @PathVariable @Positive Long ticketId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success(supportTicketService.adminUpdateStatus(
                ticketId, body.get("status"), body.get("resolutionNotes"))));
    }

    private Long parseCustomerId(String header) {
        if (header == null || !header.matches("\\d+")) {
            throw new BusinessException("X-Customer-Id header is required");
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException ex) {
            throw new BusinessException("X-Customer-Id header is invalid");
        }
    }
}