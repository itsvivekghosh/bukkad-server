package com.bhukkad.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InstanceMetadataTest {

    @Test
    void getInstanceId_returnsNonBlankValue() {
        InstanceMetadata metadata = new InstanceMetadata();

        assertNotNull(metadata.getInstanceId());
        assertFalse(metadata.getInstanceId().isBlank());
    }
}
