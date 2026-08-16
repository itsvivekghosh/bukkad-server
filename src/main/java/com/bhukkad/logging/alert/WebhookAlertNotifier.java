package com.bhukkad.logging.alert;

import com.bhukkad.config.AlertingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookAlertNotifier {

    private final AlertingProperties alertingProperties;
    private final RestClient restClient = RestClient.create();

    public void sendIfEnabled(AlertSeverity severity,
                            AlertCategory category,
                            String message,
                            Map<String, Object> payload) {
        AlertingProperties.Webhook webhook = alertingProperties.getWebhook();
        if (!alertingProperties.isEnabled() || !webhook.isEnabled() || !StringUtils.hasText(webhook.getUrl())) {
            return;
        }
        try {
            restClient.post()
                    .uri(webhook.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("ALERT_WEBHOOK_FAILED | category={} | severity={} | message={} | error={}",
                    category, severity, message, ex.getMessage());
        }
    }
}
