package com.bhukkad.common.datasource;

/**
 * Thread-local that marks the current request/thread as read-replica eligible
 * (port of {@code com.bhukkad.datasource.ReadReplicaContext}).
 */
public final class ReadReplicaContext {

    private static final ThreadLocal<ReadReplicaType> CONTEXT = new ThreadLocal<>();

    private ReadReplicaContext() {
    }

    public static void set(ReadReplicaType type) {
        CONTEXT.set(type);
    }

    public static ReadReplicaType get() {
        ReadReplicaType type = CONTEXT.get();
        return type != null ? type : ReadReplicaType.PRIMARY;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
