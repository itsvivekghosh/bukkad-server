package com.bhukkad.cache.http;

import com.bhukkad.cache.RedisCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Generates and validates HTTP cache validators (ETag / Last-Modified) for
 * cached restaurant and menu responses.
 */
@Component
public class HttpCacheSupport {

    private static final Logger log = LoggerFactory.getLogger(HttpCacheSupport.class);
    private static final String ETAG_PREFIX = "W/\"bhukkad-";

    private final RedisCacheService cacheService;
    private final ObjectMapper objectMapper;

    public HttpCacheSupport(RedisCacheService cacheService, ObjectMapper objectMapper) {
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds cache headers for a cached response.
     *
     * @param cacheKey      the cache key used to store the value
     * @param responseBody  the serialized response body
     * @return headers map containing ETag and Cache-Control
     */
    public HttpHeaders buildCacheHeaders(String cacheKey, String responseBody) {
        HttpHeaders headers = new HttpHeaders();
        String etag = generateEtag(responseBody);
        headers.setETag(etag);
        headers.setCacheControl("public, max-age=60, must-revalidate");
        return headers;
    }

    /**
     * Builds cache headers from a last-modified timestamp.
     */
    public HttpHeaders buildCacheHeaders(LocalDateTime lastModified) {
        HttpHeaders headers = new HttpHeaders();
        if (lastModified != null) {
            long epochSeconds = lastModified.toEpochSecond(ZoneOffset.UTC);
            headers.setLastModified(epochSeconds * 1000);
        }
        headers.setCacheControl("public, max-age=60, must-revalidate");
        return headers;
    }

    /**
     * Returns {@code true} if the request's If-None-Match matches the given ETag.
     */
    public boolean isNotModified(String requestEtag, String responseEtag) {
        return responseEtag != null && responseEtag.equals(requestEtag);
    }

    /**
     * Returns {@code true} if the request's If-Modified-Since is after the given timestamp.
     */
    public boolean isNotModified(Long requestModifiedSince, LocalDateTime lastModified) {
        if (requestModifiedSince == null || lastModified == null) {
            return false;
        }
        long lastModifiedMillis = lastModified.toEpochSecond(ZoneOffset.UTC) * 1000;
        return requestModifiedSince >= lastModifiedMillis;
    }

    private String generateEtag(String body) {
        // Weak ETag based on body hash would be ideal, but to keep this
        // dependency-light we use a simple timestamp-based weak ETag when the
        // body is empty, otherwise a content-based marker.
        if (body == null || body.isEmpty()) {
            return ETAG_PREFIX + System.currentTimeMillis() + "\"";
        }
        int hash = body.hashCode();
        return ETAG_PREFIX + Integer.toHexString(hash) + "\"";
    }
}
