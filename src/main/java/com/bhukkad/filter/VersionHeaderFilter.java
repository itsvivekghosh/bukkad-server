package com.bhukkad.filter;

import com.bhukkad.config.VersionProperties;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Filter to handle API versioning via headers.
 * <p>
 * Checks for an {@code Accept-Version} or {@code X-API-Version} header in the request.
 * If the version is unsupported, returns 400 Bad Request.
 * If the version is deprecated, adds a warning header to the response.
 * Always adds the current API version to the response header {@code X-API-Version}.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class VersionHeaderFilter implements Filter {

    private final VersionProperties versionProperties;

    private static final String HEADER_REQUEST_VERSION = "Accept-Version";
    private static final String HEADER_REQUEST_VERSION_ALT = "X-API-Version";
    private static final String HEADER_RESPONSE_VERSION = "X-API-Version";
    private static final String HEADER_RESPONSE_WARNING = "Warning";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Determine the requested version from headers
        String requestedVersion = httpRequest.getHeader(HEADER_REQUEST_VERSION);
        if (requestedVersion == null) {
            requestedVersion = httpRequest.getHeader(HEADER_REQUEST_VERSION_ALT);
        }
        // If no version header is provided, assume the current version
        if (requestedVersion == null || requestedVersion.isEmpty()) {
            requestedVersion = versionProperties.getCurrentVersion();
        }

        // Check if the version is unsupported
        if (versionProperties.getUnsupportedVersions().contains(requestedVersion)) {
            httpResponse.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Unsupported API version: " + requestedVersion);
            return;
        }

        // Check if the version is deprecated and add a warning header
        if (versionProperties.getDeprecatedVersions().contains(requestedVersion)) {
            String warningMessage = "299 - \"Deprecated API version " + requestedVersion
                    + ". Please upgrade to version " + versionProperties.getCurrentVersion() + "\"";
            httpResponse.addHeader(HEADER_RESPONSE_WARNING, warningMessage);
            log.warn("Deprecated API version used: {} (current: {})",
                    requestedVersion, versionProperties.getCurrentVersion());
        }

        // Set the current version in the response header
        httpResponse.setHeader(HEADER_RESPONSE_VERSION, versionProperties.getCurrentVersion());

        chain.doFilter(request, response);
    }
}