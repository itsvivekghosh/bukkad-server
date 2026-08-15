package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * One recorded abuse signal: a single sensitive attempt (registration, login, checkout, refund,
 * promo redemption) together with the actor identifiers it was made under.
 *
 * <p>Rows are append-only. Nothing updates or deletes a fraud event, because the table is not a
 * state machine — it is the evidence trail that velocity checks count over. Each row is written by
 * {@code FraudDetectionService} <em>before</em> the guarded operation is allowed to proceed, so an
 * attempt is recorded even when it is subsequently rejected.</p>
 *
 * <h2>How rows become a block</h2>
 * <p>{@code FraudDetectionService.checkAndBlock} counts rows matching {@link #eventType} plus one
 * actor identifier ({@link #ipAddress} or {@link #deviceFingerprint}) inside the sliding window
 * from {@code FraudProperties.getWindowMinutes()}. If either count reaches the per-type threshold
 * from {@code FraudProperties.thresholdFor(String)}, a {@code FraudBlockedException} is raised,
 * which {@code GlobalExceptionHandler} maps to <strong>HTTP 429</strong> with {@code Retry-After}.
 * Counting is type-scoped so unrelated activity cannot push a legitimate user over an unrelated
 * threshold. Enforcement is separately switchable via {@code app.fraud.blocking-enabled}; with it
 * off, rows are still written and this entity keeps accumulating the data needed to choose
 * thresholds.</p>
 *
 * <h2>Identifier nullability</h2>
 * <p>Only {@link #eventType} and {@link #createdAt} are guaranteed. The customer is absent for
 * pre-authentication events (registration, and login attempts that never resolve to an account),
 * and either network identifier can be missing when the client does not supply a fingerprint
 * header or the address cannot be determined. A velocity check simply skips whichever identifier
 * is absent rather than treating {@code null} as a matchable value.</p>
 *
 * <p>Read paths are backed by the composite indexes added in
 * {@code V17__trust_and_compliance.sql}; see {@code FraudEventRepository} for the queries.</p>
 *
 * @see com.bhukkad.fraud.FraudEventTypes
 * @see com.bhukkad.fraud.FraudProperties
 * @see com.bhukkad.repository.FraudEventRepository
 */
@Entity
@Table(name = "fraud_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class FraudEvent {

    /** Surrogate key; assigned by the database on insert. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Customer the attempt is attributed to, or {@code null} for pre-authentication events.
     *
     * <p>LAZY and never touched by enforcement: velocity checks count by
     * {@code customer_id} rather than navigating the association, which matters because
     * {@code spring.jpa.open-in-view} is {@code false} and a detached access would fail.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    /**
     * Which guarded operation this row represents — always one of the constants in
     * {@code FraudEventTypes}, never a free-form string, since the value participates in every
     * velocity query and in the V17 composite indexes.
     */
    @Column(nullable = false, length = 50)
    private String eventType;

    /**
     * Client-supplied device identifier, or {@code null} when the client sends none.
     *
     * <p>Spoofable by design — it is a correlation hint that makes bulk abuse from one device
     * visible, not an authentication factor.</p>
     */
    @Column(length = 128)
    private String deviceFingerprint;

    /** Origin address; sized for IPv6. {@code null} when it cannot be determined. */
    @Column(length = 45)
    private String ipAddress;

    /**
     * Free-form context for humans reviewing the admin fraud feed (for example the email or phone
     * used on a registration attempt). Never parsed by enforcement logic, so its shape is free to
     * change; must not be used to carry credentials or other secrets.
     */
    @Column(columnDefinition = "TEXT")
    private String details;

    /**
     * Insert timestamp, populated by JPA auditing and immutable thereafter.
     *
     * <p>This is the window boundary every velocity query filters on, so it must reflect when the
     * attempt happened and is therefore never set by application code.</p>
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
