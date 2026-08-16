package com.bhukkad.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Helpers for pulling values out of the current HTTP request.
 *
 * <p>Two kinds of helper live here:</p>
 * <ul>
 *   <li><b>Header parsing</b> ({@link #extractTokenFromRequestHeaders(String)}) — operates on a
 *       header value the caller already has, typically bound with {@code @RequestHeader}.</li>
 *   <li><b>Ambient request lookups</b> ({@link #resolveClientIp()},
 *       {@link #resolveDeviceFingerprint()}) — read from the thread-bound request via
 *       {@link RequestContextHolder}, so services can obtain abuse signals without every
 *       controller having to inject {@link HttpServletRequest}.</li>
 * </ul>
 *
 * <p><b>Thread-affinity warning:</b> the ambient lookups only work on the servlet thread that is
 * handling the request. Code running on an {@code @Async} executor (for example the asynchronous
 * order-create path) sees an empty {@link RequestContextHolder} and gets {@link #UNKNOWN_IP} /
 * {@code null}. Capture these values in the controller and pass them down when the work is handed
 * to another thread.</p>
 */
public final class RequestUtils {

    /**
     * Fallback returned by {@link #resolveClientIp()} when no request is bound to the current
     * thread or the container cannot report a remote address. Stored as-is on fraud events so the
     * gap is visible in audits rather than silently becoming {@code null}.
     */
    public static final String UNKNOWN_IP = "unknown";

    /**
     * Client-supplied stable device identifier. Mobile clients are expected to send a per-install
     * UUID; it is advisory only (a hostile client can forge or omit it), which is why IP-based
     * counting always runs alongside it.
     */
    public static final String DEVICE_FINGERPRINT_HEADER = "X-Device-Fingerprint";

    /**
     * Proxy headers inspected, in order, before falling back to the socket address. Matches the
     * list already used by the request-logging filter so both produce the same attribution.
     */
    private static final String[] FORWARDED_FOR_HEADERS = {
            "X-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_X_FORWARDED_FOR",
            "HTTP_CLIENT_IP"
    };

    /** Longest fingerprint accepted; matches {@code fraud_events.device_fingerprint} (128). */
    private static final int MAX_FINGERPRINT_LENGTH = 128;

    /** Longest IP accepted; matches {@code fraud_events.ip_address} (45, i.e. IPv6 + zone). */
    private static final int MAX_IP_LENGTH = 45;

    private RequestUtils() {
        // Static utility holder.
    }

    /**
     * Extracts the bearer token from an {@code Authorization} header value.
     *
     * @param authHeader raw header value, may be {@code null}
     * @return the token without the {@code Bearer } prefix
     * @throws RuntimeException if the header is missing or not a bearer header
     */
    public static String extractTokenFromRequestHeaders(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Invalid or missing Authorization header. Use: Bearer <token>");
        }
        return authHeader.substring(7);
    }

    /**
     * Resolves the calling client's IP address for the request bound to the current thread.
     *
     * <p>The first non-blank proxy header wins; {@code X-Forwarded-For} may carry a chain
     * ({@code client, proxy1, proxy2}) so only the left-most entry is used. When no proxy header is
     * present the socket address is returned.</p>
     *
     * <p><b>Trust model:</b> forwarded headers are client-controlled unless a trusted proxy
     * overwrites them. Values from this method are therefore usable for abuse throttling and
     * auditing, but must never be treated as an authentication factor.</p>
     *
     * @return a non-null, non-blank address, or {@link #UNKNOWN_IP} when it cannot be determined
     */
    public static String resolveClientIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return UNKNOWN_IP;
        }
        for (String header : FORWARDED_FOR_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value.trim())) {
                String first = value.split(",")[0].trim();
                if (!first.isEmpty()) {
                    return truncate(first, MAX_IP_LENGTH);
                }
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr == null || remoteAddr.isBlank())
                ? UNKNOWN_IP
                : truncate(remoteAddr, MAX_IP_LENGTH);
    }

    /**
     * Reads the {@value #DEVICE_FINGERPRINT_HEADER} header from the current request.
     *
     * @return the trimmed fingerprint, or {@code null} when the client did not send one (which is
     *         the common case for the web client and for older mobile builds)
     */
    public static String resolveDeviceFingerprint() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        return normalizeFingerprint(request.getHeader(DEVICE_FINGERPRINT_HEADER));
    }

    /**
     * Normalises a fingerprint supplied by a controller (for example bound with
     * {@code @RequestHeader}) so it fits the persisted column and never stores blanks.
     *
     * @param fingerprint raw header value, may be {@code null}
     * @return trimmed and length-capped value, or {@code null} if blank
     */
    public static String normalizeFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return null;
        }
        return truncate(fingerprint.trim(), MAX_FINGERPRINT_LENGTH);
    }

    /**
     * @return the request bound to the current thread, or {@code null} outside a servlet request
     *         (async executors, scheduled jobs, message consumers)
     */
    private static HttpServletRequest currentRequest() {
        try {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttributes) {
                return servletAttributes.getRequest();
            }
        } catch (RuntimeException ex) {
            // No usable request context; callers fall back to their own defaults.
            return null;
        }
        return null;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
