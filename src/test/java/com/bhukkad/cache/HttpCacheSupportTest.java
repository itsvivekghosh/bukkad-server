package com.bhukkad.cache;

import com.bhukkad.cache.http.HttpCacheSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HttpCacheSupportTest {

    @Mock
    private RedisCacheService redisCacheService;

    @InjectMocks
    private HttpCacheSupport support;

    @Test
    void buildCacheHeaders_setsEtagAndCacheControl() {
        var headers = support.buildCacheHeaders("restaurant:1", "{\"id\":1}");

        assertNotNull(headers);
        assertNotNull(headers.getETag());
        assertTrue(headers.getCacheControl().toString().contains("max-age=60"));
    }

    @Test
    void buildCacheHeaders_setsLastModified() {
        var headers = support.buildCacheHeaders(LocalDateTime.of(2024, 1, 1, 0, 0));

        assertNotNull(headers);
        assertNotNull(headers.getLastModified());
    }

    @Test
    void isNotModified_matchingEtag_returnsTrue() {
        assertTrue(support.isNotModified("W/\"abc\"", "W/\"abc\""));
    }

    @Test
    void isNotModified_nonMatchingEtag_returnsFalse() {
        assertFalse(support.isNotModified("W/\"abc\"", "W/\"def\""));
    }

    @Test
    void isNotModified_nullEtag_returnsFalse() {
        assertFalse(support.isNotModified(null, "W/\"abc\""));
    }

    @Test
    void isNotModified_matchingLastModified_returnsTrue() {
        long modified = LocalDateTime.of(2024, 1, 1, 0, 0).toEpochSecond(java.time.ZoneOffset.UTC) * 1000;
        assertTrue(support.isNotModified(modified, LocalDateTime.of(2024, 1, 1, 0, 0)));
    }

    @Test
    void isNotModified_olderLastModified_returnsFalse() {
        long older = LocalDateTime.of(2023, 1, 1, 0, 0).toEpochSecond(java.time.ZoneOffset.UTC) * 1000;
        assertFalse(support.isNotModified(older, LocalDateTime.of(2024, 1, 1, 0, 0)));
    }
}
