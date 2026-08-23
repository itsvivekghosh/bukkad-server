package com.bhukkad.health;

import com.bhukkad.config.SyntheticHealthCheckProperties;
import com.bhukkad.logging.alert.AlertCategory;
import com.bhukkad.logging.alert.AlertService;
import com.bhukkad.logging.alert.AlertSeverity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Runs synthetic health checks against critical endpoints on a schedule.
 * <p>
 * Any failing endpoint raises an alert via {@link AlertService} so ops is
 * notified before real users are affected. Failures are contained: a broken
 * probe never breaks the scheduler or the request path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyntheticHealthCheckService {

    private final SyntheticHealthCheckProperties properties;
    private final AlertService alertService;
    private final RestTemplate restTemplate;

    @Value("${local.server.port:${server.port:8080}}")
    private int port;

    /**
     * Runs synthetic health checks at a fixed interval. For each configured
     * endpoint, sends an HTTP GET and alerts on failure or non-2xx status.
     */
    @Scheduled(fixedDelayString = "${app.synthetic-health.interval-ms:60000}")
    public void runHealthChecks() {
        String baseUrl = "http://localhost:" + port;
        for (String endpoint : properties.getEndpoints()) {
            String url = baseUrl + endpoint;
            try {
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    log.debug("Synthetic health check passed for {}: {}", endpoint, response.getStatusCode());
                } else {
                    String message = String.format("Synthetic health check failed for %s: returned status %s",
                            endpoint, response.getStatusCode());
                    alertService.alert(AlertSeverity.WARNING, AlertCategory.SYSTEM, message);
                    log.warn(message);
                }
            } catch (HttpStatusCodeException e) {
                String message = String.format("Synthetic health check failed for %s: HTTP %s",
                        endpoint, e.getStatusCode());
                alertService.alert(AlertSeverity.WARNING, AlertCategory.SYSTEM, message);
                log.warn(message);
            } catch (ResourceAccessException e) {
                String message = String.format("Synthetic health check failed for %s: unable to connect",
                        endpoint);
                alertService.alert(AlertSeverity.WARNING, AlertCategory.SYSTEM, message);
                log.warn(message, e);
            } catch (Exception e) {
                String message = String.format("Synthetic health check failed for %s: %s",
                        endpoint, e.getMessage());
                alertService.alert(AlertSeverity.WARNING, AlertCategory.SYSTEM, message);
                log.warn(message);
            }
        }
    }
}