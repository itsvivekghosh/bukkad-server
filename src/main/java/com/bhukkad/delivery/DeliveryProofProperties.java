package com.bhukkad.delivery;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for proof-of-delivery handover.
 *
 * <p>Bound from {@code app.delivery.proof.*}. Self-registering with
 * {@link Component} rather than being listed in an
 * {@code @EnableConfigurationProperties} block, matching the convention used by
 * the other trust-and-compliance properties classes such as
 * {@code FraudProperties}.</p>
 *
 * <p><strong>Rollout guidance.</strong> {@link #enforced} is the safety valve.
 * Deploy with {@code enforced=false} first: riders can request and verify OTPs,
 * every outcome is recorded, but a missing or failed proof still does not stop
 * {@code markOrderDelivered}. Once the rider app is shipped and the verified
 * rate looks healthy in the logs, flip it to {@code true} to actually block
 * completion. Enabling it before the rider app can present the OTP screen would
 * strand every in-flight delivery.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.delivery.proof")
public class DeliveryProofProperties {

    /**
     * Master switch for the feature. When {@code false} the endpoints reject
     * requests and delivery completion is never gated, which is the pre-V17
     * behaviour.
     */
    private boolean enabled = true;

    /**
     * Whether an unsatisfied proof blocks {@code markOrderDelivered}.
     *
     * <p>Deliberately defaulted to {@code false} so upgrading the backend alone
     * cannot break deliveries; see the rollout note on this class.</p>
     */
    private boolean enforced = false;

    /**
     * Minutes a freshly issued OTP stays valid.
     *
     * <p>Defaults to {@code Constants.OTP_EXPIRY_MINUTES} (10). Short enough
     * that a code overheard earlier in the day is useless, long enough to
     * survive a rider hunting for the right flat.</p>
     */
    private int otpExpiryMinutes = 10;

    /**
     * Failed verification attempts allowed against a single code before the
     * proof is marked {@code FAILED} and a reissue is required.
     *
     * <p>Six digits is a million-value space, so a handful of attempts is not a
     * brute-force risk on its own; the cap exists to stop a compromised rider
     * account from grinding through codes on many orders at once.</p>
     */
    private int maxOtpAttempts = 5;

    /**
     * Minimum seconds between reissues for the same order.
     *
     * <p>Prevents a rider tapping "resend" from spamming the customer's phone
     * (and the SMS bill).</p>
     */
    private int otpResendCooldownSeconds = 60;
}
