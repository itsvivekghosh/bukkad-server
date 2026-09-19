package com.bhukkad.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Enriches MDC with environment metadata so every log line carries
 * environment, version, and region labels automatically.
 */
@Component
public class LogMetadataEnricher {

    private static final Logger log = LoggerFactory.getLogger("STARTUP");

    private final Environment environment;

    public LogMetadataEnricher(Environment environment) {
        this.environment = environment;
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        String env = environment.getProperty("app.environment", "unknown");
        String version = environment.getProperty("app.version", "unknown");
        String region = environment.getProperty("app.region", "unknown");
        String serviceName = environment.getProperty("spring.application.name", "unknown");

        MDC.put("environment", env);
        MDC.put("version", version);
        MDC.put("region", region);
        MDC.put("service", serviceName);

        log.info("LOG_METADATA_ENRICHED | service={} | environment={} | version={} | region={}",
            serviceName, env, version, region);
    }
}
