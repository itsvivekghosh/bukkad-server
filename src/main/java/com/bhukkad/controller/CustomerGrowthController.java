package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.request.SubscribeMembershipRequest;
import com.bhukkad.dto.request.SupportTicketRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.MembershipPlanResponse;
import com.bhukkad.dto.response.MembershipStatusResponse;
import com.bhukkad.dto.response.SupportTicketResponse;
import com.bhukkad.dto.response.WalletTransactionResponse;
import com.bhukkad.membership.MembershipService;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.support.SupportTicketService;
import com.bhukkad.wallet.WalletQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Customer growth features: wallet history, support tickets, membership.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/customers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerGrowthController {

    private final WalletQueryService walletQueryService;
    private final SupportTicketService supportTicketService;
    private final MembershipService membershipService;
    private final SecurityUtils securityUtils;

    /** Paginated wallet transaction history (offset). Retained for back-compat. */
    @GetMapping("/wallet/transactions")
    public ResponseEntity<ApiResponse<com.bhukkad.dto.response.PagedResponse<WalletTransactionResponse>>> getWalletTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                walletQueryService.getTransactions(securityUtils.getCurrentUserId(), page, size)));
    }

    /**
     * Cursor-paginated wallet transaction history. Preferred over the offset
     * variant for unbounded histories — keeps query cost constant regardless of
     * scroll depth.
     */
    @GetMapping("/wallet/transactions/cursor")
    public ResponseEntity<ApiResponse<com.bhukkad.dto.response.CursorPagedResponse<WalletTransactionResponse>>> getWalletTransactionsByCursor(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                walletQueryService.getTransactionsByCursor(securityUtils.getCurrentUserId(), cursor, size)));
    }

    /** Create a support ticket. */
    @PostMapping("/support/tickets")
    public ResponseEntity<ApiResponse<SupportTicketResponse>> createSupportTicket(
            @RequestBody SupportTicketRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Support ticket created", supportTicketService.create(request)));
    }

    /** List customer's support tickets. */
    @GetMapping("/support/tickets")
    public ResponseEntity<ApiResponse<List<SupportTicketResponse>>> listSupportTickets() {
        return ResponseEntity.ok(ApiResponse.success(supportTicketService.listCustomerTickets()));
    }

    /** List available membership plans (public within customer auth). */
    @GetMapping("/membership/plans")
    public ResponseEntity<ApiResponse<List<MembershipPlanResponse>>> listMembershipPlans() {
        return ResponseEntity.ok(ApiResponse.success(membershipService.listPlans()));
    }

    /** Get active membership status. */
    @GetMapping("/membership/status")
    public ResponseEntity<ApiResponse<MembershipStatusResponse>> getMembershipStatus() {
        return ResponseEntity.ok(ApiResponse.success(
                membershipService.getActiveMembership(securityUtils.getCurrentUserId())));
    }

    /** Subscribe to a membership plan. */
    @PostMapping("/membership/subscribe")
    public ResponseEntity<ApiResponse<MembershipStatusResponse>> subscribeMembership(
            @RequestBody SubscribeMembershipRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Membership activated", membershipService.subscribe(request)));
    }
}
