package com.bhukkad.config;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DegradationPropertiesTest {

    @Test
    void isEnabled_returnsTrueForMissingKey() {
        DegradationProperties props = new DegradationProperties();
        props.setFeatures(Map.of("some-feature", true));
        assertTrue(props.isEnabled("non-existent-key"));
    }

    @Test
    void isEnabled_returnsTrueWhenEnabled() {
        DegradationProperties props = new DegradationProperties();
        props.setFeatures(Map.of("home-feed", true));
        assertTrue(props.isEnabled("home-feed"));
    }

    @Test
    void isEnabled_returnsFalseWhenDisabled() {
        DegradationProperties props = new DegradationProperties();
        props.setFeatures(Map.of("home-feed", false));
        assertFalse(props.isEnabled("home-feed"));
    }

    @Test
    void isEnabled_returnsTrueForEmptyFeatures() {
        DegradationProperties props = new DegradationProperties();
        props.setFeatures(Map.of());
        assertTrue(props.isEnabled("any-feature"));
    }

    @Test
    void getFeatures_returnsSetMap() {
        DegradationProperties props = new DegradationProperties();
        props.setFeatures(Map.of("f1", true, "f2", false));
        Map<String, Boolean> features = props.getFeatures();
        assertEquals(2, features.size());
        assertTrue(features.get("f1"));
        assertFalse(features.get("f2"));
    }
}