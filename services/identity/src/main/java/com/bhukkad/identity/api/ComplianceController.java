package com.bhukkad.identity.api;

import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.identity.domain.ConsentRecord;
import com.bhukkad.identity.domain.ConsentRecordRepository;
import com.bhukkad.identity.domain.CustomerRepository;
import com.bhukkad.identity.domain.DeviceTokenRepository;
import com.bhukkad.identity.service.ConsentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Customer self-service DPDP/GDPR compliance surface: consent registry,
 * consent withdrawal and data-portability export. Identity is always the
 * authenticated subject (no path ids — cross-customer consent access is
 * impossible by construction).
 */
@RestController
@RequestMapping("/api/v1/compliance")
@RequiredArgsConstructor
public class ComplianceController {

    private final ConsentService consentService;
    private final ConsentRecordRepository consentRepository;
    private final CustomerRepository customerRepository;
    private final DeviceTokenRepository deviceTokenRepository;

    public record ConsentBody(Boolean granted) {}

    @GetMapping("/consents")
    @Transactional(readOnly = true)
    public List<ConsentRecord> consents(@AuthenticationPrincipal TokenPrincipal principal) {
        return consentRepository.findByUserId(subjectId(principal));
    }

    /** Records (or updates) the caller's consent for {@code purpose}. */
    @PutMapping("/consents/{purpose}")
    @Transactional
    public ConsentRecord setConsent(@AuthenticationPrincipal TokenPrincipal principal,
                                    @PathVariable String purpose,
                                    @RequestBody(required = false) ConsentBody body) {
        Long customerId = subjectId(principal);
        boolean granted = body == null || body.granted() == null || body.granted();
        return consentService.record(customerId, purpose, granted);
    }

    @DeleteMapping("/consents/{purpose}")
    @Transactional
    public Map<String, String> withdrawConsent(@AuthenticationPrincipal TokenPrincipal principal,
                                               @PathVariable String purpose) {
        consentService.withdraw(subjectId(principal), purpose);
        return Map.of("message", "Consent withdrawn");
    }

    @GetMapping("/consents/{purpose}/satisfied")
    @Transactional(readOnly = true)
    public Map<String, Object> consentSatisfied(@AuthenticationPrincipal TokenPrincipal principal,
                                                @PathVariable String purpose) {
        return Map.of("purpose", purpose, "satisfied",
                consentService.hasConsent(subjectId(principal), purpose));
    }

    /**
     * Data-portability export: 202 with a job id plus the (synchronously
     * assembled) export document. The dev build completes immediately; the
     * monolith parity contract (202 + jobId) is preserved for the apps.
     */
    @PostMapping("/export")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> export(
            @AuthenticationPrincipal TokenPrincipal principal) {
        Long customerId = subjectId(principal);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", LocalDateTime.now().toString());
        customerRepository.findById(customerId).ifPresent(c -> {
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("id", c.getId());
            profile.put("fullName", c.getFullName() == null ? "" : c.getFullName());
            profile.put("email", c.getEmail() == null ? "" : c.getEmail());
            data.put("profile", profile);
        });
        data.put("consents", consentRepository.findByUserId(customerId));
        data.put("devices", deviceTokenRepository.findByUserId(customerId));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", "export-" + customerId + "-" + System.currentTimeMillis());
        body.put("status", "COMPLETED");
        body.put("downloadUrl", "/api/v1/customers/data-export");
        body.put("data", data);
        return ResponseEntity.accepted().body(body);
    }

    private static Long subjectId(TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated customer required");
        }
        return principal.userId();
    }
}
