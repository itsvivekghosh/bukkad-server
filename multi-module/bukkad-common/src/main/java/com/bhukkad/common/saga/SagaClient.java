package com.bhukkad.common.saga;

/**
 * Client contract for the saga coordinator. Each service invokes the saga
 * on its local {@code saga_instances} / {@code saga_steps} tables; the
 * step definitions are registered at startup.
 */
public interface SagaClient {

    /**
     * Starts or resumes a saga for a given business key.
     *
     * @param sagaType e.g. "ORDER_CREATION"
     * @param sagaId   business key, e.g. orderId.toString()
     * @param payload  JSON payload
     * @return terminal status: "COMPLETED" or "COMPENSATED"
     */
    String executeSaga(String sagaType, String sagaId, String payload);
}