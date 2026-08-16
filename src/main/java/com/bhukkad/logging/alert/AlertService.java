package com.bhukkad.logging.alert;

import com.bhukkad.config.AlertingProperties;
import com.bhukkad.logging.LoggingConstants;
import com.bhukkad.logging.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AlertService {

    private static final Logger alertLogger = LoggerFactory.getLogger(LoggingConstants.ALERT_LOGGER);

    private final AlertingProperties alertingProperties;
    private final ObjectMapper objectMapper;
    private final WebhookAlertNotifier webhookAlertNotifier;

    private final ConcurrentHashMap<String, Long> recentAlerts = new ConcurrentHashMap<>();

    public void alert(AlertSeverity severity, AlertCategory category, String message) {
        alert(severity, category, message, Map.of());
    }

    public void alert(AlertSeverity severity, AlertCategory category, String message, Map<String, Object> context) {
        if (!alertingProperties.isEnabled()) {
            return;
        }

        String dedupKey = category.name() + ":" + severity.name() + ":" + message;
        if (!shouldFire(dedupKey)) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("severity", severity.name());
        payload.put("category", category.name());
        payload.put("message", message);
        payload.put("traceId", TraceContext.getTraceId());
        payload.put("requestId", TraceContext.getRequestId());
        payload.putAll(context);

        try {
            String json = objectMapper.writeValueAsString(payload);
            switch (severity) {
                case CRITICAL -> alertLogger.error(json);
                case WARNING -> alertLogger.warn(json);
                default -> alertLogger.info(json);
            }
        } catch (Exception ex) {
            alertLogger.warn("ALERT | {} | {} | {} | traceId={} | requestId={}",
                    severity, category, message, TraceContext.getTraceId(), TraceContext.getRequestId());
        }

        webhookAlertNotifier.sendIfEnabled(severity, category, message, payload);
    }

    public void alertSlowRequest(String method, String uri, long durationMs, int status) {
        long critical = alertingProperties.getSlowRequest().getCriticalThresholdMs();
        long warning = alertingProperties.getSlowRequest().getWarningThresholdMs();
        if (durationMs < warning) {
            return;
        }
        AlertSeverity severity = durationMs >= critical ? AlertSeverity.CRITICAL : AlertSeverity.WARNING;
        alert(severity, AlertCategory.SLOW_REQUEST,
                "Slow request detected",
                Map.of("method", method, "uri", uri, "durationMs", durationMs, "status", status));
    }

    public void alertHttpError(String method, String uri, int status, long durationMs) {
        if (status >= 500 && alertingProperties.getHttpError().isAlertOn5xx()) {
            alert(AlertSeverity.CRITICAL, AlertCategory.HTTP_ERROR,
                    "Server error response",
                    Map.of("method", method, "uri", uri, "status", status, "durationMs", durationMs));
            return;
        }
        if (status >= 400 && alertingProperties.getHttpError().isAlertOn4xx()) {
            AlertCategory category = status == 401 || status == 403
                    ? AlertCategory.SECURITY
                    : AlertCategory.HTTP_ERROR;
            AlertSeverity severity = status == 401 || status == 403
                    ? AlertSeverity.WARNING
                    : AlertSeverity.INFO;
            alert(severity, category,
                    "Client error response",
                    Map.of("method", method, "uri", uri, "status", status, "durationMs", durationMs));
        }
    }

    public void alertException(String source, String message, Throwable throwable) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", source);
        if (throwable != null && StringUtils.hasText(throwable.getClass().getSimpleName())) {
            context.put("exceptionType", throwable.getClass().getSimpleName());
        }
        alert(AlertSeverity.CRITICAL, AlertCategory.EXCEPTION, message, context);
    }

    private boolean shouldFire(String key) {
        long now = System.currentTimeMillis();
        long windowMs = alertingProperties.getDedupWindowSeconds() * 1000L;
        Long last = recentAlerts.put(key, now);
        if (recentAlerts.size() > 2000) {
            recentAlerts.entrySet().removeIf(entry -> now - entry.getValue() > windowMs);
        }
        return last == null || now - last >= windowMs;
    }
}
