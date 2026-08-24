package com.bhukkad.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SloPropertiesTest {

    @Test
    void defaultsAreSane() {
        SloProperties props = new SloProperties();
        assertEquals(500L, props.getTargetLatencyMs());
        assertEquals(99.9, props.getTargetAvailabilityPercentage());
        assertEquals(60, props.getEvaluationWindowMinutes());
        assertEquals(1.0, props.getAlertBurnRateThreshold());
    }

    @Test
    void settersAndGettersRoundTrip() {
        SloProperties props = new SloProperties();
        props.setTargetLatencyMs(250L);
        props.setTargetAvailabilityPercentage(99.99);
        props.setEvaluationWindowMinutes(30);
        props.setAlertBurnRateThreshold(2.0);

        assertEquals(250L, props.getTargetLatencyMs());
        assertEquals(99.99, props.getTargetAvailabilityPercentage());
        assertEquals(30, props.getEvaluationWindowMinutes());
        assertEquals(2.0, props.getAlertBurnRateThreshold());
    }

    @Test
    void allowsEdgeValues() {
        SloProperties props = new SloProperties();
        props.setTargetLatencyMs(0L);
        props.setTargetAvailabilityPercentage(100.0);
        props.setEvaluationWindowMinutes(1);
        props.setAlertBurnRateThreshold(0.01);

        assertEquals(0L, props.getTargetLatencyMs());
        assertEquals(100.0, props.getTargetAvailabilityPercentage());
        assertEquals(1, props.getEvaluationWindowMinutes());
        assertEquals(0.01, props.getAlertBurnRateThreshold());
    }
}