package com.bhukkad.serviceImpl;

import com.bhukkad.ratelimit.RateLimitProperties;
import com.bhukkad.config.SyntheticHealthCheckProperties;
import com.bhukkad.config.VersionProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for config changes made in the API test fix PR.
 */
class BatchEndpointsConfigTest {

    // ── MenuServiceImpl.getMenuItemsByIds (via existing MenuServiceImplTest) ─
    // The service method tests live in MenuServiceImplTest and
    // RestaurantServiceImplTest which have the full mock setup.

    @Test
    void rateLimitProperties_hasReferralBucket() {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Bucket bucket = props.getBucket("referral");
        assertNotNull(bucket);
        assertEquals(20, bucket.getLimit());
        assertEquals(60, bucket.getWindowSeconds());
    }

    @Test
    void rateLimitProperties_hasDefaultBuckets() {
        RateLimitProperties props = new RateLimitProperties();
        assertNotNull(props.getBucket("order-track"));
        assertNotNull(props.getBucket("search"));
        assertNotNull(props.getBucket("referral"));
    }

    @Test
    void versionProperties_unsupportedIncludesZero() {
        VersionProperties props = new VersionProperties();
        assertTrue(props.getUnsupportedVersions().contains("0"));
    }

    @Test
    void versionProperties_currentVersionIsOne() {
        VersionProperties props = new VersionProperties();
        assertEquals("1", props.getCurrentVersion());
    }

    @Test
    void syntheticHealthCheck_doesNotIncludeParamDependentEndpoints() {
        SyntheticHealthCheckProperties props = new SyntheticHealthCheckProperties();
        // The serviceability endpoint requires mandatory query params
        // (restaurantId, latitude, longitude) — a health check call without
        // them would 400, so it must NOT be in the synthetic check list.
        assertFalse(props.getEndpoints().stream()
                .anyMatch(e -> e.contains("/serviceability/check")),
                "Param-dependent endpoints must not be in synthetic health check list");
    }

    @Test
    void syntheticHealthCheck_hasHealthEndpoint() {
        SyntheticHealthCheckProperties props = new SyntheticHealthCheckProperties();
        assertTrue(props.getEndpoints().stream()
                .anyMatch(e -> e.contains("/actuator/health")));
    }
}