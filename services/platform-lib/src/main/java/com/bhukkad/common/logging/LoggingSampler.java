package com.bhukkad.common.logging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Probabilistic logging sampler for production. Controls which requests/
 * service calls are logged to avoid log volume explosion while keeping
 * enough signal for debugging.
 *
 * <p>Configured via application.yml:</p>
 * <pre>
 * logging:
 *   sampling:
 *     http: 0.1      # 10% of HTTP requests
 *     service: 0.5   # 50% of service calls
 * </pre>
 */
@Component
public class LoggingSampler {

    private static final Random RANDOM = new Random();

    private final double httpRate;
    private final double serviceRate;

    public LoggingSampler(
            @Value("${logging.sampling.http:1.0}") double httpRate,
            @Value("${logging.sampling.service:1.0}") double serviceRate) {
        this.httpRate = clamp(httpRate);
        this.serviceRate = clamp(serviceRate);
    }

    /**
     * Should this HTTP request be logged?
     */
    public boolean shouldLogHttp() {
        return RANDOM.nextDouble() < httpRate;
    }

    /**
     * Should this service call be logged?
     */
    public boolean shouldLogService() {
        return RANDOM.nextDouble() < serviceRate;
    }

    /**
     * Should this specific event be logged? Uses the service rate.
     */
    public boolean shouldLogEvent() {
        return RANDOM.nextDouble() < serviceRate;
    }

    private double clamp(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }
}
