package com.bhukkad.datasource;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
