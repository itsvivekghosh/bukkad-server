package com.bhukkad.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface DeliveryAssignmentRepository extends JpaRepository<DeliveryAssignment, Long> {
    Optional<DeliveryAssignment> findByOrderId(Long orderId);

    java.util.List<DeliveryAssignment> findByAgentId(Long agentId);

    java.util.List<DeliveryAssignment> findByAgentIdAndStatusIgnoreCase(Long agentId, String status);

    /**
     * ADR-003 capacity check: per-agent count of still-open assignments, in
     * one grouped query (no N+1) executed in the same transaction as the
     * conditional insert below — the {@code UPPER(status)}<> DELIVERED
     * predicate mirrors {@link #markDeliveredIfOpen}. The legacy schema has no
     * {@code active_load} column (a counter would need its own lifecycle
     * decrement), so the count IS the load — best-effort at pick time, with
     * the INSERT...ON CONFLICT (order_id) guard remaining the real double-claim
     * arbiter.
     */
    @Query("SELECT new com.bhukkad.delivery.domain.AgentActiveLoad(a.agentId, COUNT(a)) "
            + "FROM DeliveryAssignment a WHERE a.agentId IN :agentIds "
            + "AND UPPER(a.status) <> 'DELIVERED' GROUP BY a.agentId")
    java.util.List<AgentActiveLoad> countActiveLoadByAgentIds(
            @Param("agentIds") java.util.Collection<Long> agentIds);

    /**
     * Atomic insert guard (audit B10): the {@code uq_delivery_assignments_order}
     * constraint from migration V8 decides the winner between racing
     * {@code assign()} callers — 1 row inserted means this transaction owns the
     * assignment, 0 rows means someone else already holds it for the order.
     * {@code created_at} is passed explicitly because the native statement
     * bypasses JPA auditing.
     */
    @Modifying
    @Query(value = "INSERT INTO delivery_assignments "
            + "(order_id, agent_id, status, assigned_at, created_at) "
            + "VALUES (:orderId, :agentId, :status, :assignedAt, :assignedAt) "
            + "ON CONFLICT (order_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("orderId") Long orderId,
                       @Param("agentId") Long agentId,
                       @Param("status") String status,
                       @Param("assignedAt") LocalDateTime assignedAt);

    /**
     * Conditional delivery transition (audit B10 TOCTOU): flips the row to
     * DELIVERED only if it is not DELIVERED yet. Exactly one racing caller
     * receives the 1-row answer and may publish OrderDelivered; the rest get
     * 0 rows and must treat it as an idempotent no-op. PostgreSQL re-evaluates
     * the predicate against the latest committed row after a row-lock wait, so
     * a concurrent winner's commit turns the loser into 0 rows rather than a
     * second event. {@code UPPER()} keeps legacy mixed-case statuses honest.
     */
    @Modifying
    @Query(value = "UPDATE delivery_assignments "
            + "SET status = :deliveredStatus, delivered_at = :deliveredAt "
            + "WHERE order_id = :orderId AND UPPER(status) <> UPPER(:deliveredStatus)",
            nativeQuery = true)
    int markDeliveredIfOpen(@Param("orderId") Long orderId,
                            @Param("deliveredStatus") String deliveredStatus,
                            @Param("deliveredAt") LocalDateTime deliveredAt);
}
