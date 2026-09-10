package com.bhukkad.referral.api;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.referral.dto.request.AffiliateSignupRequest;
import com.bhukkad.referral.dto.request.ApplyReferralRequest;
import com.bhukkad.referral.dto.request.InternalCodeRequest;
import com.bhukkad.referral.dto.request.ReferralValidateRequest;
import com.bhukkad.referral.dto.response.AffiliateCodeResponse;
import com.bhukkad.referral.dto.response.AffiliateStatsResponse;
import com.bhukkad.referral.dto.response.ApiResponse;
import com.bhukkad.referral.dto.response.ReferralInfoResponse;
import com.bhukkad.referral.service.AffiliateService;
import com.bhukkad.referral.service.ReferralService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Referral codes and the affiliate program.
 *
 * <p>Paths match the monolith's ReferralServiceClient/AffiliateServiceClient
 * contract: the monolith resolves the caller and rate-limits, then proxies.
 * Code validation is public; everything else is authenticated (the monolith
 * authenticates its customer on their behalf until JWT propagation).</p>
 */
@RestController
@RequestMapping("/api/v1/referrals")
@Tag(name = "Referral", description = "Personal referral codes and the affiliate program")
public class ReferralController {

    private final ReferralService referralService;
    private final AffiliateService affiliateService;

    public ReferralController(ReferralService referralService, AffiliateService affiliateService) {
        this.referralService = referralService;
        this.affiliateService = affiliateService;
    }

    @GetMapping("/customers/{customerId}/info")
    @Operation(summary = "Get referral rewards information for a customer")
    public ResponseEntity<ApiResponse<ReferralInfoResponse>> getReferralInfo(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable @Positive Long customerId) {
        // IDOR guard: referral bonus balances are per-customer data.
        requireSelfOrAdmin(principal, customerId);
        ReferralInfoResponse info = referralService.getReferralInfo(customerId);
        return ResponseEntity.ok(ApiResponse.success(info));
    }

    @PostMapping("/customers/{customerId}/code")
    @Operation(summary = "Generate referral code for a customer (idempotent)")
    public ResponseEntity<ApiResponse<Map<String, String>>> generateReferralCode(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable @Positive Long customerId) {
        requireSelfOrAdmin(principal, customerId);
        String referralCode = referralService.generateAndSaveReferralCode(customerId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("referralCode", referralCode)));
    }

    private static void requireSelfOrAdmin(TokenPrincipal principal, Long customerId) {
        if (principal == null || principal.userId() == null) {
            throw new com.bhukkad.common.error.UnauthorizedException("Authentication required");
        }
        boolean admin = "ADMIN".equalsIgnoreCase(principal.scope() == null ? "" : principal.scope());
        if (!admin && !principal.userId().equals(customerId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Cannot access another customer's referral data");
        }
    }

    // --- Customer-facing (self) endpoints that resolve identity from the JWT ---

    @PostMapping("/generate")
    @Operation(summary = "Generate referral code for the authenticated customer (idempotent)")
    public ResponseEntity<ApiResponse<Map<String, String>>> generateMyReferralCode(
            @AuthenticationPrincipal TokenPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(new ApiResponse<>(false, "Authentication required", null));
        }
        String referralCode = referralService.generateAndSaveReferralCode(principal.userId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("referralCode", referralCode)));
    }

    @PostMapping("/validate")
    @Operation(summary = "Validate a referral code (POST body)")
    public ResponseEntity<ApiResponse<Boolean>> validateReferralCode(
            @Valid @RequestBody ReferralValidateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(referralService.isValidReferralCode(request.referralCode())));
    }

    @GetMapping("/rewards")
    @Operation(summary = "Get referral rewards summary for the authenticated customer")
    public ResponseEntity<ApiResponse<ReferralInfoResponse>> getMyReferralRewards(
            @AuthenticationPrincipal TokenPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(new ApiResponse<>(false, "Authentication required", null));
        }
        ReferralInfoResponse info = referralService.getReferralInfo(principal.userId());
        return ResponseEntity.ok(ApiResponse.success(info));
    }

    @GetMapping("/validate/{code}")
    @Operation(summary = "Validate a referral code (public)")
    public ResponseEntity<ApiResponse<Boolean>> validateReferralCode(@PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.success(referralService.isValidReferralCode(code)));
    }

    /**
     * Internal: called by the registration flow to link a new customer to a
     * referrer and bump the referrer's counters. Idempotent (ADR-005): a
     * customer already carrying {@code referredBy} is never re-bound and the
     * reward is credited exactly once.
     */
    @PostMapping("/internal/apply")
    @Operation(summary = "Apply a referral code to a new signup", description = "Service-to-service")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> applyReferral(@Valid @RequestBody ApplyReferralRequest request) {
        ReferralService.ApplyReferralOutcome outcome = referralService.applyReferral(
                request.customerId(), request.customerEmail(), request.referralCode());
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("applied", outcome.applied());
        body.put("firstBinding", outcome.firstBinding());
        if (outcome.referrerCustomerId() != null) {
            body.put("referrerCustomerId", outcome.referrerCustomerId());
        }
        return ResponseEntity.accepted().body(ApiResponse.success("Referral processed", body));
    }

    /**
     * Internal: single code generator (ADR-005) — identity/growth call this
     * instead of generating codes themselves. Idempotent per customer.
     */
    @PostMapping("/internal/code")
    @Operation(summary = "Generate (or fetch) a customer's referral code", description = "Service-to-service")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> generateCodeInternal(
            @Valid @RequestBody InternalCodeRequest request) {
        String referralCode = referralService.generateAndSaveReferralCode(request.customerId());
        return ResponseEntity.ok(ApiResponse.success("Referral code ready",
                Map.of("referralCode", referralCode)));
    }

    /**
     * Internal: records a signup attributed to an affiliate code.
     */
    @PostMapping("/internal/affiliate-signup")
    @Operation(summary = "Record an affiliate-attributed signup", description = "Service-to-service")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> recordAffiliateSignup(
            @Valid @RequestBody AffiliateSignupRequest request) {
        affiliateService.recordSignup(request.code(), request.customerId(), request.customerEmail());
        return ResponseEntity.accepted().body(ApiResponse.success("Affiliate signup recorded", null));
    }

    // ----- Affiliate admin (monolith admin controller proxies with ADMIN role) -----

    @GetMapping("/affiliates")
    @Operation(summary = "List all affiliate codes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<AffiliateCodeResponse>>> listAffiliates() {
        return ResponseEntity.ok(ApiResponse.success(affiliateService.listAll()));
    }

    @PostMapping("/affiliates")
    @Operation(summary = "Create affiliate code")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AffiliateCodeResponse>> createAffiliate(
            @Valid @RequestBody com.bhukkad.referral.dto.request.AffiliateCodeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Affiliate code created",
                affiliateService.create(request)));
    }

    @PutMapping("/affiliates/{affiliateId}")
    @Operation(summary = "Update affiliate code")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AffiliateCodeResponse>> updateAffiliate(
            @PathVariable @Positive Long affiliateId,
            @Valid @RequestBody com.bhukkad.referral.dto.request.AffiliateCodeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Affiliate code updated",
                affiliateService.update(affiliateId, request)));
    }

    @DeleteMapping("/affiliates/{affiliateId}")
    @Operation(summary = "Deactivate affiliate code")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deactivateAffiliate(@PathVariable @Positive Long affiliateId) {
        affiliateService.deactivate(affiliateId);
        return ResponseEntity.ok(ApiResponse.success("Affiliate code deactivated", null));
    }

    @GetMapping("/affiliates/{affiliateId}/stats")
    @Operation(summary = "Get affiliate stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AffiliateStatsResponse>> affiliateStats(
            @PathVariable @Positive Long affiliateId) {
        return ResponseEntity.ok(ApiResponse.success(affiliateService.getStats(affiliateId)));
    }
}