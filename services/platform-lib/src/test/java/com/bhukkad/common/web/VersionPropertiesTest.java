package com.bhukkad.common.web;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VersionPropertiesTest {

    @Test
    void defaultsAreSane() {
        VersionProperties props = new VersionProperties();
        assertEquals("1", props.getCurrentVersion());
        assertTrue(props.getDeprecatedVersions().isEmpty());
        assertEquals(List.of("0"), props.getUnsupportedVersions());
    }

    @Test
    void settersAndGettersRoundTrip() {
        VersionProperties props = new VersionProperties();
        props.setCurrentVersion("2");
        props.setDeprecatedVersions(List.of("1"));
        props.setUnsupportedVersions(List.of("0"));

        assertEquals("2", props.getCurrentVersion());
        assertEquals(List.of("1"), props.getDeprecatedVersions());
        assertEquals(List.of("0"), props.getUnsupportedVersions());
    }
}