package com.bhukkad.common.tracing;

import java.util.Optional;

/**
 * W3C Trace Context {@code traceparent} header value
 * ({@code version-traceid-spanid-flags}, e.g. {@code 00-<32 hex>-<16 hex>-01}).
 *
 * <p>Propagated inside the event envelope (plan §9) so a trace that starts in
 * one service can be reconstructed in the consuming service without an agent.</p>
 *
 * @param version 2-char hex version ({@code 00})
 * @param traceId 32-char lowercase hex
 * @param spanId  16-char lowercase hex
 * @param flags   2-char hex flags ({@code 01} = sampled)
 */
public record Traceparent(String version, String traceId, String spanId, String flags) {

    private static final String FORMAT = "%s-%s-%s-%s";

    public Traceparent {
        if (version == null || !version.matches("[0-9a-f]{2}")) {
            throw new IllegalArgumentException("Invalid traceparent version: " + version);
        }
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid traceId (need 32 hex): " + traceId);
        }
        if (spanId == null || !spanId.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("Invalid spanId (need 16 hex): " + spanId);
        }
        if (flags == null || !flags.matches("[0-9a-f]{2}")) {
            throw new IllegalArgumentException("Invalid flags (need 2 hex): " + flags);
        }
    }

    public static Traceparent create(String traceId, String spanId, boolean sampled) {
        return new Traceparent("00", traceId, spanId, sampled ? "01" : "00");
    }

    /** Parses a traceparent header, tolerating malformed/blank input. */
    public static Optional<Traceparent> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String[] parts = value.trim().split("-");
        if (parts.length != 4) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Traceparent(parts[0], parts[1], parts[2], parts[3]));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean isSampled() {
        return (Integer.parseInt(flags, 16) & 0x01) == 0x01;
    }

    /** Re-derives a traceparent string, e.g. for a child span in the next service. */
    public String childSpan(String newSpanId) {
        return new Traceparent(version, traceId, newSpanId, flags).toString();
    }

    @Override
    public String toString() {
        return FORMAT.formatted(version, traceId, spanId, flags);
    }
}
