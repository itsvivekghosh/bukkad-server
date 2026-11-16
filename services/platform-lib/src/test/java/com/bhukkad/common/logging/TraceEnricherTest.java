package com.bhukkad.common.logging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceEnricherTest {

    @Mock
    private Tracer tracer;

    @Mock
    private Span span;

    private TraceEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new TraceEnricher(tracer);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void enrichCurrentSpan_copiesBusinessContextFromMdc() {
        when(tracer.currentSpan()).thenReturn(span);
        when(span.isNoop()).thenReturn(false);
        MDC.put(LoggingConstants.USER_ID, "user-42");
        MDC.put(LoggingConstants.TENANT_ID, "tenant-7");
        MDC.put(LoggingConstants.ORDER_ID, "order-99");
        MDC.put(LoggingConstants.RESTAURANT_ID, "restaurant-3");

        enricher.enrichCurrentSpan();

        verify(span).tag(LoggingConstants.USER_ID, "user-42");
        verify(span).tag(LoggingConstants.TENANT_ID, "tenant-7");
        verify(span).tag(LoggingConstants.ORDER_ID, "order-99");
        verify(span).tag(LoggingConstants.RESTAURANT_ID, "restaurant-3");
    }

    @Test
    void enrichCurrentSpan_skipsAbsentAndBlankValues() {
        when(tracer.currentSpan()).thenReturn(span);
        when(span.isNoop()).thenReturn(false);
        MDC.put(LoggingConstants.USER_ID, "user-42");
        MDC.put(LoggingConstants.TENANT_ID, "  ");

        enricher.enrichCurrentSpan();

        verify(span).tag(LoggingConstants.USER_ID, "user-42");
        verify(span, never()).tag(LoggingConstants.TENANT_ID, "  ");
        verify(span, never()).tag(LoggingConstants.ORDER_ID, null);
        verify(span, never()).tag(LoggingConstants.RESTAURANT_ID, null);
    }

    @Test
    void enrichCurrentSpan_ignoresNoopSpan() {
        when(tracer.currentSpan()).thenReturn(span);
        when(span.isNoop()).thenReturn(true);
        MDC.put(LoggingConstants.USER_ID, "user-42");

        enricher.enrichCurrentSpan();

        verify(span, never()).tag(LoggingConstants.USER_ID, "user-42");
    }

    @Test
    void enrichCurrentSpan_ignoresMissingSpan() {
        when(tracer.currentSpan()).thenReturn(null);

        enricher.enrichCurrentSpan();

        verifyNoInteractions(span);
    }
}
