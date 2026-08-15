package com.bhukkad.fraud;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.FraudEvent;
import com.bhukkad.exception.FraudBlockedException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.FraudEventRepository;
import com.bhukkad.util.RequestUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Records abuse signals and blocks callers whose velocity exceeds the configured thresholds.
 *
 * <h2>What this protects that rate limiting does not</h2>
 * <p>{@code @RateLimited} keys its buckets on the authenticated user id, or on the submitted email
 * for login. An attacker who creates a fresh email per attempt therefore never reuses a bucket key
 * and is invisible to it. This service counts by <b>network origin</b> and <b>device</b> instead, so
 * bulk registration and card-testing from one host are caught even when every request carries new
 * credentials. The two mechanisms are complementary and both stay enabled.</p>
 *
 * <h2>Counting model</h2>
 * <p>Every attempt is written to {@code fraud_events} first, then counted over a sliding window
 * ({@link FraudProperties#getWindowMinutes()}). Because the write happens before the count, the
 * current attempt is included: a {@code perIp} threshold of 10 allows 9 prior attempts and blocks
 * the 10th. Counting is scoped to the event type, so login traffic cannot exhaust the registration
 * allowance.</p>
 *
 * <h2>Failure posture</h2>
 * <p>Enforcement <b>fails open</b>. If the fraud store is unreachable or a query throws, the request
 * proceeds and the failure is logged. A database hiccup must not take down login, registration and
 * checkout simultaneously; the same choice was already made for Redis-backed rate limiting.</p>
 *
 * <h2>Transaction boundaries</h2>
 * <p>{@link #checkAndBlock} runs in its own {@link Propagation#REQUIRES_NEW} transaction so the
 * audit row survives even when the caller's transaction later rolls back — including the rollback
 * caused by the {@link FraudBlockedException} this method throws. Without that, a blocked attempt
 * would leave no trace and the attacker's counter would never advance.</p>
 */
@Service
@RequiredArgsConstructor
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);

    /**
     * Legacy threshold retained for {@link #checkAndLog(Long, String, String, String)}, whose
     * contract (any 5 events of any type in one hour is suspicious) predates the configurable
     * per-event-type thresholds and is kept for callers that only want a boolean signal.
     */
    private static final int SUSPICIOUS_EVENT_THRESHOLD = 5;
    private static final int LOOKBACK_HOURS = 1;

    private final FraudEventRepository fraudEventRepository;
    private final CustomerRepository customerRepository;
    private final FraudProperties fraudProperties;

    /**
     * Records an event and rejects the request when the caller is over the threshold for that event
     * type. IP and device fingerprint are read from the request bound to the current thread.
     *
     * <p>Must be called on the servlet thread — {@link RequestUtils#resolveClientIp()} cannot see
     * the request from an {@code @Async} executor. For work that is handed off to another thread,
     * resolve the signals in the controller and call
     * {@link #checkAndBlock(Long, String, String, String)}.</p>
     *
     * @param customerId authenticated customer, or {@code null} for anonymous endpoints
     * @param eventType  label from {@link FraudEventTypes}
     * @throws FraudBlockedException when the velocity threshold is exceeded and blocking is enabled
     */
    public void checkAndBlock(Long customerId, String eventType) {
        checkAndBlock(customerId, eventType,
                RequestUtils.resolveDeviceFingerprint(), RequestUtils.resolveClientIp());
    }

    /**
     * Records an event with explicitly supplied signals and rejects the request when the caller is
     * over the threshold for that event type.
     *
     * @param customerId  authenticated customer, or {@code null} for anonymous endpoints
     * @param eventType   label from {@link FraudEventTypes}
     * @param fingerprint device fingerprint, or {@code null} when the client did not send one
     * @param ip          client IP address
     * @throws FraudBlockedException when the velocity threshold is exceeded and blocking is enabled
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public void checkAndBlock(Long customerId, String eventType, String fingerprint, String ip) {
        if (!fraudProperties.isEnabled()) {
            return;
        }

        FraudProperties.Threshold threshold = fraudProperties.thresholdFor(eventType);
        String normalizedFingerprint = RequestUtils.normalizeFingerprint(fingerprint);
        String normalizedIp = StringUtils.hasText(ip) ? ip : RequestUtils.UNKNOWN_IP;

        long ipCount;
        long deviceCount;
        try {
            persistEvent(customerId, eventType, normalizedFingerprint, normalizedIp);

            LocalDateTime since = LocalDateTime.now().minusMinutes(fraudProperties.getWindowMinutes());
            ipCount = RequestUtils.UNKNOWN_IP.equals(normalizedIp)
                    ? 0
                    : fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                            eventType, normalizedIp, since);
            deviceCount = normalizedFingerprint == null
                    ? 0
                    : fraudEventRepository.countByEventTypeAndDeviceFingerprintAndCreatedAtAfter(
                            eventType, normalizedFingerprint, since);
        } catch (RuntimeException ex) {
            log.error("FRAUD_CHECK_FAILED | eventType={} | reason={}", eventType, ex.getMessage());
            return;
        }

        if (!exceedsThreshold(ipCount, threshold.getPerIp()) &&
            !exceedsThreshold(deviceCount, threshold.getPerDevice())) {
            return;
        }

        String dimension = exceedsThreshold(ipCount, threshold.getPerIp()) ? "ip" : "device";
        long observed = exceedsThreshold(ipCount, threshold.getPerIp()) ? ipCount : deviceCount;
        long limit = exceedsThreshold(ipCount, threshold.getPerIp()) ? threshold.getPerIp() : threshold.getPerDevice();

        if (!fraudProperties.isBlockingEnabled()) {
            log.warn("FRAUD_WOULD_BLOCK | eventType={} | dimension={} | count={} | limit={} | ip={}",
                    eventType, dimension, observed, limit, normalizedIp);
            return;
        }

        log.warn("FRAUD_BLOCKED | eventType={} | dimension={} | count={} | limit={} | ip={} | customerId={}",
                eventType, dimension, observed, limit, normalizedIp, customerId);
        throw new FraudBlockedException(
                "Unusual activity detected from your device or network. Please try again later.",
                eventType, fraudProperties.getRetryAfterSeconds());
    }

    private boolean exceedsThreshold(long count, int threshold) {
        return threshold > 0 && count >= threshold;
    }

    /**
     * Records an event and reports whether the pattern looks suspicious, without rejecting the
     * request.
     *
     * <p>Uses the original fixed threshold ({@value #SUSPICIOUS_EVENT_THRESHOLD} events of any type
     * from one IP or device within {@value #LOOKBACK_HOURS} hour) rather than the configurable
     * per-event-type thresholds. Prefer {@link #checkAndBlock(Long, String)} for anything that
     * should actually stop abuse.</p>
     *
     * @param customerId  optional customer identifier
     * @param eventType   event type label
     * @param fingerprint device fingerprint
     * @param ip          client IP address
     * @return {@code true} if the event pattern is suspicious
     */
    @Transactional(readOnly = false)
    public boolean checkAndLog(Long customerId, String eventType, String fingerprint, String ip) {
        persistEvent(customerId, eventType, RequestUtils.normalizeFingerprint(fingerprint), ip);

        LocalDateTime since = LocalDateTime.now().minusHours(LOOKBACK_HOURS);
        long fingerprintCount = StringUtils.hasText(fingerprint)
                ? fraudEventRepository.countByDeviceFingerprintAndCreatedAtAfter(fingerprint, since)
                : 0;
        long ipCount = StringUtils.hasText(ip)
                ? fraudEventRepository.countByIpAddressAndCreatedAtAfter(ip, since)
                : 0;

        return fingerprintCount >= SUSPICIOUS_EVENT_THRESHOLD || ipCount >= SUSPICIOUS_EVENT_THRESHOLD;
    }

    /**
     * Writes one audit row. The customer link is optional and looked up leniently: an unknown or
     * missing id records an anonymous event rather than failing the caller's request.
     */
    private void persistEvent(Long customerId, String eventType, String fingerprint, String ip) {
        FraudEvent event = new FraudEvent();
        event.setEventType(eventType);
        event.setDeviceFingerprint(fingerprint);
        event.setIpAddress(ip);

        if (customerId != null) {
            Customer customer = customerRepository.findById(customerId).orElse(null);
            event.setCustomer(customer);
        }

        fraudEventRepository.save(event);
    }
}
