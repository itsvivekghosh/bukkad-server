package com.bhukkad.datasource;

public final class ReadReplicaContext {

    private static final ThreadLocal<ReadReplicaType> CONTEXT =
            ThreadLocal.withInitial(() -> ReadReplicaType.PRIMARY);

    private ReadReplicaContext() {}

    public static ReadReplicaType get() {
        return CONTEXT.get();
    }

    public static void set(ReadReplicaType type) {
        CONTEXT.set(type != null ? type : ReadReplicaType.PRIMARY);
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
