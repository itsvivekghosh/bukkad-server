package com.bhukkad.repository;

import com.bhukkad.entity.FraudEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Access to recorded fraud/abuse signals.
 *
 * <p>All counting queries are velocity queries: "how many events of this shape happened since
 * {@code since}". They are backed by the composite indexes added in
 * {@code V17__trust_and_compliance.sql}
 * ({@code idx_fraud_ip_type_created}, {@code idx_fraud_fingerprint_type_created}); the original
 * V13 table only indexed {@code customer_id} and {@code event_type}, which would have made every
 * enforcement check a full table scan.</p>
 */
@Repository
public interface FraudEventRepository extends JpaRepository<FraudEvent, Long> {

    /** Total events from a device in the window, regardless of event type. */
    long countByDeviceFingerprintAndCreatedAtAfter(String deviceFingerprint, LocalDateTime since);

    /** Total events from an IP in the window, regardless of event type. */
    long countByIpAddressAndCreatedAtAfter(String ipAddress, LocalDateTime since);

    /**
     * Events of one type from a device in the window. Type-scoped counting is what enforcement
     * uses, so unrelated activity (for example browsing-driven signals) cannot push a legitimate
     * user over the login or registration threshold.
     */
    long countByEventTypeAndDeviceFingerprintAndCreatedAtAfter(
            String eventType, String deviceFingerprint, LocalDateTime since);

    /** Events of one type from an IP in the window. */
    long countByEventTypeAndIpAddressAndCreatedAtAfter(
            String eventType, String ipAddress, LocalDateTime since);

    /** Events of one type attributed to a customer in the window. */
    long countByEventTypeAndCustomerIdAndCreatedAtAfter(
            String eventType, Long customerId, LocalDateTime since);

    /** Most recent events, newest first — backs the admin fraud feed. */
    List<FraudEvent> findTop100ByOrderByCreatedAtDesc();
}
