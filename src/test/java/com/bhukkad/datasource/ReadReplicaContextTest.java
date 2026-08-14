package com.bhukkad.datasource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadReplicaContextTest {

    @AfterEach
    void tearDown() {
        ReadReplicaContext.clear();
    }

    @Test
    void defaultsToPrimary() {
        assertEquals(ReadReplicaType.PRIMARY, ReadReplicaContext.get());
    }

    @Test
    void setAndRestoreReplicaRouting() {
        ReadReplicaContext.set(ReadReplicaType.REPLICA);
        assertEquals(ReadReplicaType.REPLICA, ReadReplicaContext.get());

        ReadReplicaContext.clear();
        assertEquals(ReadReplicaType.PRIMARY, ReadReplicaContext.get());
    }
}
