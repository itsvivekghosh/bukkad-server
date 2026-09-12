package com.bhukkad.admin.api.controller;

import com.bhukkad.admin.domain.entity.ConsentRecord;
import com.bhukkad.admin.domain.service.ConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Customer-facing DPDP/GDPR consent endpoints (ported from the monolith's
 * ComplianceController consent surface).
 *
 * <p>The monolith resolves the authenticated customer and forwards its id in
 * {@code X-User-Id}; row ownership is enforced against that header until JWT
 * propagation replaces it. Reads are the customer profile page's consent
 * panel; writes are legally significant so they propagate errors.</p>
 */
@RestController
@RequestMapping("/api/v1/compliance/users/{userId}")
@Tag(name = "CustomerCompliance", description = "Customer consent self-service")
public class CustomerComplianceController {

    private final ConsentService consentService;

    public CustomerComplianceController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @GetMapping("/consents")
    @Operation(summary = "List the customer's consent records")
    public List<ConsentRecord> getConsents(
            @AuthenticationPrincipal com.bhukkad.common.security.TokenPrincipal principal,
            @PathVariable Long userId) {
        requireSelfOrAdmin(principal, userId);
        return consentService.getConsents(userId);
    }

    @PostMapping("/consents")
    @Operation(summary = "Set one consent purpose")
    public ConsentRecord setConsent(
            @AuthenticationPrincipal com.bhukkad.common.security.TokenPrincipal principal,
            @PathVariable Long userId,
            @RequestBody Map<String, Object> body) {
        // Consent records are legally significant: only the data subject
        // (or an admin) may write them, and the purpose must be a known key.
        requireSelfOrAdmin(principal, userId);
        String purpose = String.valueOf(body.get("purpose"));
        if (purpose == null || purpose.isBlank() || !purpose.matches("[a-z_]{1,64}")) {
            throw new com.bhukkad.common.error.BusinessException("Invalid consent purpose");
        }
        Boolean granted = Boolean.TRUE.equals(body.get("granted"));
        String source = body.get("source") != null ? String.valueOf(body.get("source")) : "customer-portal";
        return consentService.setConsent(userId, purpose, granted, source);
    }

    @GetMapping("/consents/{purpose}/satisfied")
    @Operation(summary = "True when the given consent purpose is granted")
    public Boolean consentSatisfied(
            @AuthenticationPrincipal com.bhukkad.common.security.TokenPrincipal principal,
            @PathVariable Long userId,
            @PathVariable @NotBlank String purpose) {
        requireSelfOrAdmin(principal, userId);
        return consentService.allConsented(userId, purpose);
    }

    @DeleteMapping("/consents")
    @Operation(summary = "Revoke every consent for the customer (erasure flow pre-step)")
    public void revokeAll(
            @AuthenticationPrincipal com.bhukkad.common.security.TokenPrincipal principal,
            @PathVariable Long userId) {
        requireSelfOrAdmin(principal, userId);
        consentService.revokeAllConsents(userId);
    }

    private static void requireSelfOrAdmin(
            com.bhukkad.common.security.TokenPrincipal principal, Long userId) {
        if (principal == null || principal.userId() == null) {
            throw new com.bhukkad.common.error.UnauthorizedException("Authentication required");
        }
        boolean admin = "ADMIN".equalsIgnoreCase(principal.scope() == null ? "" : principal.scope());
        if (!admin && !principal.userId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Cannot access another customer's consent records");
        }
    }
}