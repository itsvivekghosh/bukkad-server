package com.bhukkad.admin.api;

import com.bhukkad.admin.compliance.ConsentRecord;
import com.bhukkad.admin.compliance.ConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
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
    public List<ConsentRecord> getConsents(@PathVariable Long userId) {
        return consentService.getConsents(userId);
    }

    @PostMapping("/consents")
    @Operation(summary = "Set one consent purpose")
    public ConsentRecord setConsent(@PathVariable Long userId,
                                    @RequestBody Map<String, Object> body) {
        String purpose = String.valueOf(body.get("purpose"));
        Boolean granted = Boolean.TRUE.equals(body.get("granted"));
        String source = body.get("source") != null ? String.valueOf(body.get("source")) : "customer-portal";
        return consentService.setConsent(userId, purpose, granted, source);
    }

    @GetMapping("/consents/{purpose}/satisfied")
    @Operation(summary = "True when the given consent purpose is granted")
    public Boolean consentSatisfied(@PathVariable Long userId,
                                    @PathVariable @NotBlank String purpose) {
        return consentService.allConsented(userId, purpose);
    }

    @DeleteMapping("/consents")
    @Operation(summary = "Revoke every consent for the customer (erasure flow pre-step)")
    public void revokeAll(@PathVariable Long userId) {
        consentService.revokeAllConsents(userId);
    }
}