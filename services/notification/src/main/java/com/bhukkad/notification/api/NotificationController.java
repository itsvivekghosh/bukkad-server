package com.bhukkad.notification.api;

import com.bhukkad.notification.domain.Notification;
import com.bhukkad.notification.service.NotificationDispatchService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @PostMapping
    public Notification dispatch(@RequestBody DispatchRequest request) {
        return dispatchService.dispatch(request.channel(), request.recipient(),
                request.template(), request.subject(), request.body());
    }

    @GetMapping
    public List<Notification> history(@RequestParam String recipient, @RequestParam String channel) {
        return dispatchService.history(recipient, channel);
    }
}