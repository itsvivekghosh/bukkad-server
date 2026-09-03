package com.bhukkad.logging.alert;
import com.bhukkad.common.logging.alert.AlertCategory;
import com.bhukkad.common.logging.alert.AlertSeverity;

import com.bhukkad.config.AlertingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import org.springframework.http.MediaType;

import java.util.Map;

@Slf4j
@Component
public class WebhookAlertNotifier {

    private final AlertingProperties alertingProperties;
    private final RestClient restClient;

    public WebhookAlertNotifier(AlertingProperties alertingProperties,
                                RestClient webhookRestClient) {
        this.alertingProperties = alertingProperties;
        this.restClient = webhookRestClient;
    }

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
