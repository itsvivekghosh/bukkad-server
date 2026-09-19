package com.bhukkad.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Logs structured service metadata once the application is fully started.
 * Visible in every environment; invaluable for debugging which version/config
 * is running where.
 */
@Component
public class StartupLoggingListener {

    private static final Logger log = LoggerFactory.getLogger("STARTUP");

    private final Environment environment;

    public StartupLoggingListener(Environment environment) {
        this.environment = environment;
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        String serviceName = environment.getProperty("spring.application.name", "unknown");
        String port = environment.getProperty("server.port", "unknown");
        String env = environment.getProperty("app.environment", "unknown");
        String version = environment.getProperty("app.version", getClass().getPackage().getImplementationVersion());
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }

        log.info("SERVICE_STARTED | name={} | port={} | environment={} | version={} | host={} | activeProfiles={}",
            serviceName, port, env, version == null ? "unknown" : version, host,
            String.join(",", event.getApplicationContext().getEnvironment().getActiveProfiles())
        );
    }
}
