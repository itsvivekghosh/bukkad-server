package com.bhukkad.common.logging;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Copies request-scoped business identifiers from the SLF4J MDC onto the span
 * created by the tracing implementation in use.
 *
 * <p>The Micrometer OTel bridge maps {@link Span#tag(String, String)} to an
 * OpenTelemetry span attribute, so no direct OTel API dependency is needed
 * here. Registering as a {@link TracingObservationHandler} means the tags are
 * applied at observation stop, after business code (and the security filter)
 * has populated the MDC. {@link #enrichCurrentSpan()} is also available for
 * callers that need to enrich immediately.</p>
 */
@Component
public class TraceEnricher implements TracingObservationHandler<Observation.Context> {

    private static final List<String> BUSINESS_CONTEXT_KEYS = List.of(
            LoggingConstants.USER_ID,
            LoggingConstants.TENANT_ID,
            LoggingConstants.ORDER_ID,
            LoggingConstants.RESTAURANT_ID);

    private final Tracer tracer;

    public TraceEnricher(Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public void onStop(Observation.Context context) {
        Span span = getTracingContext(context).getSpan();
        enrich(span);
    }

    /** Enriches the span currently bound to the tracing context, if any. */
    public void enrichCurrentSpan() {
        enrich(tracer.currentSpan());
    }

    private void enrich(Span span) {
        if (span == null || span.isNoop()) {
            return;
        }
        for (String key : BUSINESS_CONTEXT_KEYS) {
            String value = MDC.get(key);
            if (value != null && !value.isBlank()) {
                span.tag(key, value);
            }
        }
    }

    @Override
    public Tracer getTracer() {
        return tracer;
    }
}
