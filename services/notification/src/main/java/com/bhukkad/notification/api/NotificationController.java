package com.bhukkad.notification.api;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.notification.domain.Notification;
import com.bhukkad.notification.service.NotificationDispatchService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Notification surface. Dispatch and history are ADMIN-only: previously any
 * user could send arbitrary content to arbitrary recipients (spam/phishing
 * relay) and read any recipient's full message history (PII leak).
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationDispatchService dispatchService;

    public record DispatchRequest(
            @NotBlank String channel,
            @NotBlank String recipient,
            String template,
            String subject,
            String body
    ) {}

    /**
     * Dispatch is ADMIN-gated (internal services call via their own clients),
     * additionally rate-limited per actor: 60 sends/min stops bulk
     * recipient-listing and blast misuse of the shared email/sms credentials.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @com.bhukkad.common.ratelimit.RateLimited(bucket = "notification-dispatch",
            limit = 60, windowSeconds = 60)
    public ResponseEntity<Notification> dispatch(@RequestBody DispatchRequest request) {
        Notification notification = dispatchService.dispatch(request.channel(), request.recipient(),
                request.template(), request.subject(), request.body());
        return ResponseEntity.ok(notification);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<Notification> history(@RequestParam String recipient, @RequestParam String channel) {
        return dispatchService.history(recipient, channel);
    }

    /**
     * Admin "test notification" (monolith parity): fires a throwaway message
     * through the given channel so the ops console can verify credentials and
     * routing without a real campaign. Enforces the same rate limit as real
     * dispatch.
     */
    @PostMapping("/test")
    @PreAuthorize("hasRole('ADMIN')")
    @com.bhukkad.common.ratelimit.RateLimited(bucket = "notification-dispatch",
            limit = 60, windowSeconds = 60)
    public java.util.Map<String, Object> test(@org.springframework.web.bind.annotation.RequestBody(
            required = false) TestNotificationRequest request) {
        String channel = request == null ? "EMAIL" : request.channel();
        String recipient = request == null ? null : request.recipient();
        if (recipient == null || recipient.isBlank()) {
            throw new com.bhukkad.common.error.BusinessException("recipient is required");
        }
        Notification notification = dispatchService.dispatch(channel, recipient,
                "TEST", "Bhukkad test notification",
                "This is a test notification triggered from the admin console.");
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("notificationId", notification.getId());
        body.put("channel", channel);
        body.put("recipient", recipient);
        body.put("status", notification.getStatus() == null ? "SENT" : notification.getStatus());
        body.put("message", "Test notification dispatched");
        return body;
    }

    public record TestNotificationRequest(String channel, String recipient) {}
}
