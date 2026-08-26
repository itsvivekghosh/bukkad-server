package com.bhukkad.datasource;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadReplicaSelectorTest {

    @Test
    void emptyKeys_fallsBackToDefaultReplicaKey() {
        ReadReplicaSelector selector = new ReadReplicaSelector(List.of());

        assertEquals(ReadReplicaType.REPLICA, selector.next());
        assertEquals(ReadReplicaType.REPLICA, selector.next());
    }

    @Test
    void nullKeys_fallsBackToDefaultReplicaKey() {
        ReadReplicaSelector selector = new ReadReplicaSelector(null);

        assertEquals(ReadReplicaType.REPLICA, selector.next());
    }

    @Test
    void roundRobinsAcrossReplicas() {
        ReadReplicaSelector selector = new ReadReplicaSelector(List.of("REPLICA_0", "REPLICA_1", "REPLICA_2"));

        assertEquals("REPLICA_0", selector.next());
        assertEquals("REPLICA_1", selector.next());
        assertEquals("REPLICA_2", selector.next());
        assertEquals("REPLICA_0", selector.next());
    }

    @Test
    void singleReplica_alwaysReturnsIt() {
        ReadReplicaSelector selector = new ReadReplicaSelector(List.of("REPLICA_0"));

        assertEquals("REPLICA_0", selector.next());
        assertEquals("REPLICA_0", selector.next());
        assertEquals("REPLICA_0", selector.next());
    }

    @Test
    void replicaCount_reflectsConfiguredReplicas() {
        assertEquals(0, new ReadReplicaSelector(List.of()).replicaCount());
        assertEquals(2, new ReadReplicaSelector(List.of("a", "b")).replicaCount());
    }

    // ==================== health-aware failover ====================

    @Test
    void markUnavailable_skipsReplicaUntilCooldownExpires() {
        ReadReplicaSelector selector = new ReadReplicaSelector(List.of("A", "B"), 60_000L);
        selector.markUnavailable("A");

        // Both picks must avoid A while it is in cooldown.
        assertEquals("B", selector.next());
        assertEquals("B", selector.next());
    }

    @Test
    void markUnavailable_allReplicasInCooldown_fallsBackToRoundRobin() {
        ReadReplicaSelector selector = new ReadReplicaSelector(List.of("A", "B"), 60_000L);
        selector.markUnavailable("A");
        selector.markUnavailable("B");

        // Degraded fleet still serves reads: falls back to a round-robin pick.
        Object pick = selector.next();
        assertTrue(pick.equals("A") || pick.equals("B"));
    }

    @Test
    void markAvailable_clearsCooldown() {
        ReadReplicaSelector selector = new ReadReplicaSelector(List.of("A", "B"), 60_000L);
        selector.markUnavailable("A");
        assertEquals("B", selector.next());

        selector.markAvailable("A");
        assertNotNull(selector.next());
    }

    @Test
    void zeroCooldown_disablesSkipping() {
        ReadReplicaSelector selector = new ReadReplicaSelector(List.of("A", "B"), 0);
        selector.markUnavailable("A");

        // With a zero cooldown the entry is immediately stale: A is healthy again.
        assertEquals("A", selector.next());
    }
}
