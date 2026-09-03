package com.bhukkad.order.api;

import com.bhukkad.order.service.DisputeResolutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Dispute API extracted from the monolith's DisputeController.
 * Customer endpoints: file a dispute, list my disputes.
 * Admin endpoints: list all, get detail, manual resolve, auto-resolve sweep.
 *
 * <p>Like the rest of the order service, caller identities are passed
 * explicitly (auth extraction is a gateway/identity concern in this slice).</p>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DisputeController {

    private final DisputeResolutionService disputeResolutionService;

    // ── Customer endpoints ───────────────────────────────────────────────────

    @PostMapping("/customers/orders/{orderId}/disputes")
    public DisputeResponse fileDispute(@PathVariable Long orderId,
                                       @RequestParam Long customerId,
                                       @Valid @RequestBody DisputeRequest request) {
        return disputeResolutionService.fileDispute(customerId, orderId, request);
    }

    @GetMapping("/customers/disputes")
    public List<DisputeResponse> myDisputes(@RequestParam Long customerId) {
        return disputeResolutionService.listForCustomer(customerId);
    }

    // ── Admin endpoints ──────────────────────────────────────────────────────

    @GetMapping("/admin/disputes")
    public List<DisputeResponse> listDisputes() {
        return disputeResolutionService.listForAdmin();
    }

    @GetMapping("/admin/disputes/{disputeId}")
    public DisputeResponse getDispute(@PathVariable Long disputeId) {
        return disputeResolutionService.getById(disputeId);
    }

    @PostMapping("/admin/disputes/{disputeId}/resolve")
    public DisputeResponse resolveDispute(@PathVariable Long disputeId,
                                          @RequestParam Long adminId,
                                          @Valid @RequestBody DisputeResolveRequest request) {
        return disputeResolutionService.manualResolve(adminId, disputeId, request);
    }

    @PostMapping("/admin/disputes/auto-resolve")
    public Map<String, Integer> autoResolve() {
        int resolved = disputeResolutionService.triggerAutoResolution();
        return Map.of("resolved", resolved);
    }
}