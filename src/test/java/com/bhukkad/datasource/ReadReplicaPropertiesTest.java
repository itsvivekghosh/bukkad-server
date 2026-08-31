package com.bhukkad.datasource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the read-replica binding defaults used by DataSourceConfig when
 * read replicas are configured for read-heavy endpoints.
 */
class ReadReplicaPropertiesTest {

    @Test
    void defaults_disabledSingleDbFallback() {
        ReadReplicaProperties props = new ReadReplicaProperties();
        assertFalse(props.isConfigured(), "must be unconfigured by default");
        assertFalse(props.hasMultipleReplicas());
        assertEquals("BhukkadReadReplicaPool", props.getHikari().getPoolName());
        assertEquals(25, props.getHikari().getMaximumPoolSize());
        assertTrue(props.getHikari().isReadOnly(), "replica pool must be read-only");
    }

    @Test
    void singleReplica_urlRequiredForConfigured() {
        ReadReplicaProperties props = new ReadReplicaProperties();
        props.setEnabled(true);
        assertFalse(props.isConfigured(), "enabled without URL must not be configured");

        props.setUrl("jdbc:postgresql://replica:5432/bhukkad");
        assertTrue(props.isConfigured());
        assertFalse(props.hasMultipleReplicas());
    }

    @Test
    void multipleReplicas_configuredWhenListNonEmpty() {
        ReadReplicaProperties props = new ReadReplicaProperties();
        props.setEnabled(true);
        ReadReplicaProperties.Replica r1 = new ReadReplicaProperties.Replica();
        r1.setUrl("jdbc:postgresql://replica1:5432/bhukkad");
        props.getReplicas().add(r1);

        assertTrue(props.isConfigured());
        assertTrue(props.hasMultipleReplicas());
        assertEquals(1, props.getReplicas().size());
    }

    @Test
    void disabledEvenWithUrl_ignored() {
        ReadReplicaProperties props = new ReadReplicaProperties();
        props.setUrl("jdbc:postgresql://replica:5432/bhukkad");
        // enabled stays false → the routing datasource falls back to primary.
        assertFalse(props.isConfigured());
    }
}