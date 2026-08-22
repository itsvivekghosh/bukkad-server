package com.bhukkad.logging.alert;

import com.bhukkad.config.AlertingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookAlertNotifierTest {

    private WebhookAlertNotifier notifier(boolean alertingEnabled, boolean webhookEnabled, String url) {
        AlertingProperties props = new AlertingProperties();
        props.setEnabled(alertingEnabled);
        AlertingProperties.Webhook webhook = new AlertingProperties.Webhook();
        webhook.setEnabled(webhookEnabled);
        webhook.setUrl(url);
        props.setWebhook(webhook);
        return new WebhookAlertNotifier(props);
    }

    @Test void disabledAlerting_doesNotCallWebhook() {
        WebhookAlertNotifier n = notifier(false, true, "http://hook");
        n.sendIfEnabled(AlertSeverity.CRITICAL, AlertCategory.HTTP_ERROR, "msg", Map.of());
    }

    @Test void disabledWebhook_doesNotCall() {
        WebhookAlertNotifier n = notifier(true, false, "http://hook");
        n.sendIfEnabled(AlertSeverity.CRITICAL, AlertCategory.PAYMENT, "msg", Map.of());
    }

    @Test void blankUrl_doesNotCall() {
        WebhookAlertNotifier n = notifier(true, true, "   ");
        n.sendIfEnabled(AlertSeverity.INFO, AlertCategory.SLOW_REQUEST, "msg", Map.of());
    }

    @Test void enabledWithUrl_postsPayload() {
        WebhookAlertNotifier n = notifier(true, true, "http://hooks.example/alert");

        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class, RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class, RETURNS_SELF);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("http://hooks.example/alert")).thenReturn(uriSpec);
        when(uriSpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);

        ReflectionTestUtils.setField(n, "restClient", restClient);
        n.sendIfEnabled(AlertSeverity.CRITICAL, AlertCategory.HTTP_ERROR, "test alert", Map.of("orderId", 5L));

        verify(uriSpec).uri("http://hooks.example/alert");
        verify(bodySpec).body(any(Map.class));
        verify(responseSpec).toBodilessEntity();
    }

    @Test void webhookFailure_isSwallowed() {
        WebhookAlertNotifier n = notifier(true, true, "http://hooks.example/alert");

        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class, RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class, RETURNS_SELF);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("http://hooks.example/alert")).thenReturn(uriSpec);
        when(uriSpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("500 from hook"));

        ReflectionTestUtils.setField(n, "restClient", restClient);
        // Must not propagate the exception
        n.sendIfEnabled(AlertSeverity.CRITICAL, AlertCategory.EXCEPTION, "boom", Map.of());
    }
}
