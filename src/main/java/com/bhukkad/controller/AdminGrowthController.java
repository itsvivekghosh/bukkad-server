package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.SupportTicketResponse;
import com.bhukkad.support.SupportTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for V13 growth features: support ticket management.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminGrowthController {

    private final SupportTicketService supportTicketService;

    /** Lists all customer support tickets. */
    @GetMapping("/support/tickets")
    public ResponseEntity<ApiResponse<List<SupportTicketResponse>>> listSupportTickets() {
        return ResponseEntity.ok(ApiResponse.success(supportTicketService.listAllForAdmin()));
    }

    /** Updates support ticket status and optional resolution notes. */
    @PutMapping("/support/tickets/{ticketId}/status")
    public ResponseEntity<ApiResponse<SupportTicketResponse>> updateTicketStatus(
            @PathVariable Long ticketId,
            @RequestParam String status,
            @RequestParam(required = false) String resolutionNotes) {
        return ResponseEntity.ok(ApiResponse.success(
                "Ticket updated",
                supportTicketService.adminUpdateStatus(ticketId, status, resolutionNotes)));
    }
}
