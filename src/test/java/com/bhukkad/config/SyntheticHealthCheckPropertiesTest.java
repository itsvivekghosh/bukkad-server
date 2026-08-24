package com.bhukkad.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticHealthCheckPropertiesTest {

    @Test
    void defaultsAreSane() {
        SyntheticHealthCheckProperties props = new SyntheticHealthCheckProperties();
        assertEquals(2, props.getEndpoints().size());
        assertTrue(props.getEndpoints().contains("/actuator/health"));
        assertEquals(60000L, props.getIntervalMs());
        assertEquals(5000L, props.getTimeoutMs());
    }

    @Test
    void settersAndGettersRoundTrip() {
        SyntheticHealthCheckProperties props = new SyntheticHealthCheckProperties();
        props.setEndpoints(List.of("/health", "/search"));
        props.setIntervalMs(30000L);
        props.setTimeoutMs(2000L);

        assertEquals(List.of("/health", "/search"), props.getEndpoints());
        assertEquals(30000L, props.getIntervalMs());
        assertEquals(2000L, props.getTimeoutMs());
    }

    @Test
    void emptyEndpointsAllowed() {
        SyntheticHealthCheckProperties props = new SyntheticHealthCheckProperties();
        props.setEndpoints(List.of());
        assertTrue(props.getEndpoints().isEmpty());
    }
}