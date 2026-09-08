package com.bhukkad.common.error;

/**
 * A downstream service in the mesh could not be reached (connection refused,
 * timeout, or restart churn). Mapped to 503 SERVICE_UNAVAILABLE — never to
 * 404, which would falsely report the requested resource as nonexistent
 * while it may well exist.
 */
public class UpstreamUnavailableException extends RuntimeException {

    private final String upstream;

    public UpstreamUnavailableException(String upstream, String message, Throwable cause) {
        super(message, cause);
        this.upstream = upstream;
    }

    public UpstreamUnavailableException(String upstream, Throwable cause) {
        this(upstream, upstream + " service unavailable", cause);
    }

    public String getUpstream() {
        return upstream;
    }
}
