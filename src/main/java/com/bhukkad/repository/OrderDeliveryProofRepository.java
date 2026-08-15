package com.bhukkad.repository;

import com.bhukkad.entity.OrderDeliveryProof;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Data access for delivery handover proofs.
 *
 * <p>Lookups are always by order because the table carries a unique constraint
 * on {@code order_id} — one proof per delivery. The proof id itself is never
 * exposed to riders, so no id-based finder is offered beyond the inherited one.
 * </p>
 */
@Repository
public interface OrderDeliveryProofRepository extends JpaRepository<OrderDeliveryProof, Long> {

    /**
     * Finds the proof for an order.
     *
     * <p>Resolved against the unique index, so this is a single-row point read
     * cheap enough to sit on the delivery-completion path.</p>
     *
     * @param orderId order to look up
     * @return the proof, or empty when none has been issued yet
     */
    Optional<OrderDeliveryProof> findByOrderId(Long orderId);

    /**
     * Whether a proof already exists for an order.
     *
     * <p>Used to decide between issuing and reissuing without loading the row
     * and its hash.</p>
     *
     * @param orderId order to check
     * @return {@code true} when a proof row exists
     */
    boolean existsByOrderId(Long orderId);

    /**
     * Proofs assigned to a rider in a given state, newest first.
     *
     * <p>Backed by {@code idx_delivery_proof_agent (agent_id, status)}. Fetches
     * the order eagerly because every caller renders the order number alongside
     * the proof and {@code open-in-view} is disabled.</p>
     *
     * @param agentId rider id
     * @param status  state to filter on
     * @return matching proofs, newest first
     */
    @Query("SELECT p FROM OrderDeliveryProof p JOIN FETCH p.order "
            + "WHERE p.agent.id = :agentId AND p.status = :status "
            + "ORDER BY p.createdAt DESC")
    List<OrderDeliveryProof> findByAgentAndStatus(@Param("agentId") Long agentId,
                                                  @Param("status") OrderDeliveryProof.ProofStatus status);
}
