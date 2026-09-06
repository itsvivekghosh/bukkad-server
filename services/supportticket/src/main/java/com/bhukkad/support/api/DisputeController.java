package com.bhukkad.support.api;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.support.dto.request.DisputeRequest;
import com.bhukkad.support.dto.request.DisputeResolveRequest;
import com.bhukkad.support.dto.response.DisputeResponse;
import com.bhukkad.support.serviceImpl.DisputeResolutionServiceImpl;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/v1"})
public class DisputeController {
    private final DisputeResolutionServiceImpl disputeResolutionService;

    public DisputeController(DisputeResolutionServiceImpl disputeResolutionService) {
        this.disputeResolutionService = disputeResolutionService;
    }

    @PostMapping(value={"/customers/orders/{orderId}/disputes"})
    @PreAuthorize(value="hasRole('CUSTOMER')")
    public ResponseEntity<DisputeResponse> fileDispute(@AuthenticationPrincipal TokenPrincipal principal,
                                                       @PathVariable Long orderId,
                                                       @Valid @RequestBody DisputeRequest request) {
        DisputeResponse dispute = this.disputeResolutionService.fileDispute(principal.userId(), orderId, request);
        return ResponseEntity.ok(dispute);
    }

    @GetMapping(value={"/customers/disputes"})
    @PreAuthorize(value="hasRole('CUSTOMER')")
    public ResponseEntity<List<DisputeResponse>> myDisputes(@AuthenticationPrincipal TokenPrincipal principal) {
        return ResponseEntity.ok(this.disputeResolutionService.listForCustomer(principal.userId()));
    }

    @GetMapping(value={"/admin/disputes"})
    @PreAuthorize(value="hasRole('ADMIN')")
    public ResponseEntity<List<DisputeResponse>> listDisputes() {
        return ResponseEntity.ok(this.disputeResolutionService.listForAdmin());
    }

    @GetMapping(value={"/admin/disputes/{disputeId}"})
    @PreAuthorize(value="hasRole('ADMIN')")
    public ResponseEntity<DisputeResponse> getDispute(@PathVariable Long disputeId) {
        return ResponseEntity.ok(this.disputeResolutionService.getById(disputeId));
    }

    @PostMapping(value={"/admin/disputes/{disputeId}/resolve"})
    @PreAuthorize(value="hasRole('ADMIN')")
    public ResponseEntity<DisputeResponse> resolveDispute(@AuthenticationPrincipal TokenPrincipal principal,
                                                          @PathVariable Long disputeId,
                                                          @Valid @RequestBody DisputeResolveRequest request) {
        DisputeResponse dispute = this.disputeResolutionService.manualResolve(principal.userId(), disputeId, request);
        return ResponseEntity.ok(dispute);
    }

    @PostMapping(value={"/admin/disputes/auto-resolve"})
    @PreAuthorize(value="hasRole('ADMIN')")
    public ResponseEntity<Map<String, Integer>> autoResolve() {
        int resolved = this.disputeResolutionService.triggerAutoResolution();
        return ResponseEntity.ok(Map.of("resolved", resolved));
    }
}

