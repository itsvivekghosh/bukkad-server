package com.bhukkad.notificationservice.web;

import com.bhukkad.notificationservice.preference.NotificationPreferenceService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Notification preferences REST surface. Mirrors the monolith's preference
 * endpoints so the gateway can route traffic without client changes.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/notification-preferences")
@RequiredArgsConstructor
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;

    @GetMapping
    public ResponseEntity<Map<String, Boolean>> getPreferences(@PathVariable @NotNull Long customerId) {
        return ResponseEntity.ok(preferenceService.getPreferences(customerId));
    }

    @PutMapping
    public ResponseEntity<Map<String, Boolean>> updatePreferences(
            @PathVariable @NotNull Long customerId,
            @RequestBody(required = false) Map<String, Boolean> updates) {
        if (updates == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        return ResponseEntity.ok(preferenceService.updatePreferences(customerId, updates));
    }
}

