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
}
