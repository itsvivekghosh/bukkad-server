package com.bhukkad.identity.api;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.identity.domain.ConsentRecord;
import com.bhukkad.identity.domain.ConsentRecordRepository;
import com.bhukkad.identity.domain.CustomerNotificationPreference;
import com.bhukkad.identity.domain.CustomerNotificationPreferenceRepository;
import com.bhukkad.identity.domain.DeviceToken;
import com.bhukkad.identity.domain.DeviceTokenRepository;
import com.bhukkad.identity.service.AccountProfileService;
import com.bhukkad.identity.service.ConsentService;
import com.bhukkad.identity.service.MembershipService;
import com.bhukkad.identity.dto.response.MembershipStatusResponse;
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
 * Self-scoped customer account surface (monolith parity for the installed
 * apps): device tokens, notification preferences, membership status, profile
 * by id, consents and DPDP data export. The acting customer is ALWAYS the
 * authenticated subject; {@code /customers/profile/{id}} additionally enforces
 * subject-or-admin.
 */
@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
public class CustomerAccountController {

    private final DeviceTokenRepository deviceTokenRepository;
    private final CustomerNotificationPreferenceRepository notificationPreferenceRepository;
    private final ConsentRecordRepository consentRepository;
    private final AccountProfileService accountProfileService;
    private final ConsentService consentService;
    private final MembershipService membershipService;
    private final com.bhukkad.identity.domain.CustomerRepository customerRepository;

    // ------------------------------------------------------------------
    // Device tokens (push notifications)
    // ------------------------------------------------------------------

    /** Registers (or refreshes) this account's push device token. */
    @PostMapping("/device-tokens")
    @Transactional
    public DeviceToken registerDevice(@AuthenticationPrincipal TokenPrincipal principal,
                                      @org.springframework.validation.annotation.Validated
                                      @RequestBody DeviceTokenRequest request) {
        Long customerId = subjectId(principal);
        return deviceTokenRepository.findByUserIdAndToken(customerId, request.getToken())
                .orElseGet(() -> accountProfileService.registerDevice(
                        customerId, request.getToken(), request.getPlatform()));
    }

    /** Lists this account's registered devices. */
    @GetMapping("/device-tokens")
    @Transactional(readOnly = true)
    public List<DeviceToken> devices(@AuthenticationPrincipal TokenPrincipal principal) {
        return deviceTokenRepository.findByUserId(subjectId(principal));
    }

    /** Deregisters a device token (logout / app uninstall). */
    @DeleteMapping("/device-tokens")
    @Transactional
    public Map<String, String> deleteDevice(@AuthenticationPrincipal TokenPrincipal principal,
                                            @RequestBody DeviceTokenRequest request) {
        Long customerId = subjectId(principal);
        long removed = deviceTokenRepository.deleteByUserIdAndToken(customerId, request.getToken());
        if (removed == 0) {
            throw new ResourceNotFoundException("Device token not registered");
        }
        return Map.of("message", "Device token removed");
    }

    // ------------------------------------------------------------------
    // Notification preferences
    // ------------------------------------------------------------------

    /**
     * Replaces the caller's per-channel notification preferences. Unknown
     * channels in the request are ignored; channels absent from the request
     * keep their stored value (upsert semantics).
     */
    @PutMapping("/notification-preferences")
    @Transactional
    public Map<String, Object> updateNotificationPreferences(
            @AuthenticationPrincipal TokenPrincipal principal,
            @RequestBody NotificationPreferencesRequest request) {
        Long customerId = subjectId(principal);
        upsertChannel(customerId, "EMAIL", request.emailEnabled());
        upsertChannel(customerId, "SMS", request.smsEnabled());
        upsertChannel(customerId, "WHATSAPP", request.whatsappEnabled());
        upsertChannel(customerId, "PUSH", request.pushEnabled());
        upsertChannel(customerId, "ORDER_UPDATES", request.orderUpdatesEnabled());
        upsertChannel(customerId, "PROMOTIONS", request.promotionsEnabled());
        Map<String, Object> body = new LinkedHashMap<>(request.asMap());
        body.put("message", "Notification preferences updated");
        return body;
    }

    @GetMapping("/notification-preferences")
    @Transactional(readOnly = true)
    public Map<String, Object> notificationPreferences(
            @AuthenticationPrincipal TokenPrincipal principal) {
        Long customerId = subjectId(principal);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("emailEnabled", channelEnabled(customerId, "EMAIL"));
        body.put("smsEnabled", channelEnabled(customerId, "SMS"));
        body.put("whatsappEnabled", channelEnabled(customerId, "WHATSAPP"));
        body.put("pushEnabled", channelEnabled(customerId, "PUSH"));
        body.put("orderUpdatesEnabled", channelEnabled(customerId, "ORDER_UPDATES"));
        body.put("promotionsEnabled", channelEnabled(customerId, "PROMOTIONS"));
        return body;
    }

    // ------------------------------------------------------------------
    // Membership self view
    // ------------------------------------------------------------------

    /** Returns the caller's active membership (or the none-projected status). */
    @GetMapping("/membership/status")
    @Transactional(readOnly = true)
    public MembershipStatusResponse membershipStatus(
            @AuthenticationPrincipal TokenPrincipal principal) {
        return membershipService.getActiveMembership(subjectId(principal));
    }

    /** Legacy path used by the customer app: plan catalogue under /customers. */
    @GetMapping("/membership/plans")
    @Transactional(readOnly = true)
    public List<?> membershipPlans(@AuthenticationPrincipal TokenPrincipal principal) {
        subjectId(principal);
        return membershipService.listPlans();
    }

    // ------------------------------------------------------------------
    // Profile by id (subject-or-admin)
    // ------------------------------------------------------------------

    @GetMapping("/profile/{customerId}")
    @Transactional(readOnly = true)
    public Map<String, Object> profileById(@AuthenticationPrincipal TokenPrincipal principal,
                                           @PathVariable Long customerId) {
        com.bhukkad.common.security.PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        var customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));
        return Map.of(
                "id", customer.getId(),
                "fullName", customer.getFullName() == null ? "" : customer.getFullName(),
                "email", customer.getEmail() == null ? "" : customer.getEmail(),
                "phoneNumber", customer.getPhoneNumber() == null ? "" : customer.getPhoneNumber());
    }

    // ------------------------------------------------------------------
    // Consents + DPDP data export
    // ------------------------------------------------------------------

    /**
     * DPDP data-portability export: the caller's profile, consents, devices
     * and addresses as a downloadable JSON document. 202 + job id keeps the
     * API contract of the async monolith export.
     */
    @PostMapping("/compliance/export")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> exportData(
            @AuthenticationPrincipal TokenPrincipal principal) {
        Long customerId = subjectId(principal);
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("generatedAt", LocalDateTime.now().toString());
        export.put("profile", customerRepository.findById(customerId)
                .map(c -> Map.of("id", c.getId(),
                        "fullName", c.getFullName() == null ? "" : c.getFullName(),
                        "email", c.getEmail() == null ? "" : c.getEmail()))
                .orElse(Map.of()));
        export.put("consents", consentRepository.findByUserId(customerId));
        export.put("devices", deviceTokenRepository.findByUserId(customerId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", "export-" + customerId + "-" + System.currentTimeMillis());
        body.put("status", "COMPLETED");
        body.put("downloadUrl", "/api/v1/customers/data-export");
        body.put("data", export);
        return ResponseEntity.accepted().body(body);
    }

    @GetMapping("/data-export")
    @Transactional(readOnly = true)
    public Map<String, Object> dataExport(@AuthenticationPrincipal TokenPrincipal principal) {
        Long customerId = subjectId(principal);
        return Map.of(
                "customerId", customerId,
                "generatedAt", LocalDateTime.now().toString(),
                "consents", consentRepository.findByUserId(customerId),
                "devices", deviceTokenRepository.findByUserId(customerId));
    }

    // ------------------------------------------------------------------
    // DTOs + helpers
    // ------------------------------------------------------------------

    @lombok.Data
    public static class DeviceTokenRequest {
        @jakarta.validation.constraints.NotBlank
        private String token;
        @jakarta.validation.constraints.NotBlank
        private String platform;
    }

    public record NotificationPreferencesRequest(Boolean emailEnabled, Boolean smsEnabled,
                                                 Boolean whatsappEnabled, Boolean pushEnabled,
                                                 Boolean orderUpdatesEnabled, Boolean promotionsEnabled) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("emailEnabled", emailEnabled);
            m.put("smsEnabled", smsEnabled);
            m.put("whatsappEnabled", whatsappEnabled);
            m.put("pushEnabled", pushEnabled);
            m.put("orderUpdatesEnabled", orderUpdatesEnabled);
            m.put("promotionsEnabled", promotionsEnabled);
            return m;
        }
    }

    public record ConsentBody(Boolean granted) {}

    private void upsertChannel(Long customerId, String channel, Boolean enabled) {
        if (enabled == null) {
            return;
        }
        notificationPreferenceRepository.findByCustomerIdAndChannel(customerId, channel)
                .ifPresentOrElse(pref -> {
                    pref.setEnabled(enabled);
                    notificationPreferenceRepository.save(pref);
                }, () -> {
                    CustomerNotificationPreference pref = new CustomerNotificationPreference();
                    pref.setCustomerId(customerId);
                    pref.setChannel(channel);
                    pref.setEnabled(enabled);
                    pref.setCreatedAt(LocalDateTime.now());
                    notificationPreferenceRepository.save(pref);
                });
    }

    private boolean channelEnabled(Long customerId, String channel) {
        return notificationPreferenceRepository
                .findByCustomerIdAndChannel(customerId, channel)
                .map(CustomerNotificationPreference::getEnabled)
                .orElse(true);
    }

    private static Long subjectId(TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new com.bhukkad.common.error.UnauthorizedException("Authenticated customer required");
        }
        return principal.userId();
    }
}
