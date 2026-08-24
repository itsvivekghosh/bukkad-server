package com.bhukkad.controller;

import com.bhukkad.compliance.ConsentRecord;
import com.bhukkad.compliance.ConsentService;
import com.bhukkad.compliance.DataDeletionService;
import com.bhukkad.compliance.DataExportRequest;
import com.bhukkad.compliance.DataExportService;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * DPDP/GDPR self-service endpoints: consent management and personal data export.
 * Erasure requests are admin-triggered via {@code POST /admin/users/{id}/erase}.
 */
@RestController
@RequestMapping("/api/v1/compliance")
@RequiredArgsConstructor
public class ComplianceController {

    private final ConsentService consentService;
    private final DataExportService dataExportService;
    private final DataDeletionService dataDeletionService;
    private final SecurityUtils securityUtils;

    @GetMapping("/consents")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<List<ConsentRecord>> getConsents() {
        return ResponseEntity.ok(consentService.getConsents(securityUtils.getCurrentUserId()));
    }

    @PutMapping("/consents/{purpose}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ConsentRecord> setConsent(
            @PathVariable("purpose") @NotBlank String purpose,
            @RequestBody Map<String, Object> body) {
        Boolean granted = Boolean.TRUE.equals(body.get("granted"));
        return ResponseEntity.ok(consentService.setConsent(
                securityUtils.getCurrentUserId(), purpose, granted, "customer-portal"));
    }

    /** Requests a fresh export of the caller's personal data. */
    @PostMapping("/export")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<DataExportRequest> requestExport() {
        DataExportRequest created = dataExportService.createRequest(securityUtils.getCurrentUserId());
        // Process synchronously: the payload is small and this avoids polling UX.
        return ResponseEntity.accepted().body(dataExportService.processRequest(created.getId()));
    }

    /** Returns the most recent ready export for the caller. */
    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<String> getExport() {
        return ResponseEntity.ok(dataExportService.getExport(securityUtils.getCurrentUserId()));
    }

    /** Admin-triggered right-to-erasure flow. */
    @PostMapping("/users/{id}/erase")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> eraseUser(@PathVariable("id") Long userId) {
        int removed = dataDeletionService.deleteUser(userId);
        return ResponseEntity.ok(ApiResponse.success(
                "User anonymized; " + removed + " address record(s) removed", null));
    }
}
