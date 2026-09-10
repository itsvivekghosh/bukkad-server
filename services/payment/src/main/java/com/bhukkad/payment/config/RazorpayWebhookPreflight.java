package com.bhukkad.payment.config;

import com.bhukkad.payment.PaymentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Webhook fail-closed preflight (audit feature #1, roadmap V-12 class; follows
 * the platform-lib {@code EventBackbonePreflight} pattern, implemented locally
 * in payment because the guard guards payment's own secret).
 *
 * <p>Refuses a <strong>prod</strong> startup when the Razorpay webhook secret
 * is unset/blank or still carries the {@code dev-webhook-secret} default:
 * {@link com.bhukkad.payment.gateway.RazorpayWebhookVerifier} authenticates
 * provider callbacks with that secret via a constant-time HMAC compare, so a
 * known default turns every unprotected deployment into an open money
 * endpoint (anyone can POST a forged {@code payment.captured}).</p>
 *
 * <p>Non-prod profiles boot untouched — dev/test keep the
 * {@code dev-webhook-secret} ergonomics.</p>
 */
@Slf4j
@Component
public class RazorpayWebhookPreflight implements EnvironmentAware {

    static final String PROD_PROFILE = "prod";
    static final String DEV_WEBHOOK_SECRET = "dev-webhook-secret";

    private Environment environment;
    private final PaymentProperties paymentProperties;

    public RazorpayWebhookPreflight(PaymentProperties paymentProperties) {
        this.paymentProperties = paymentProperties;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (environment == null || !environment.acceptsProfiles(Profiles.of(PROD_PROFILE))) {
            return; // dev/test/local keep the shared dev secret
        }
        String secret = paymentProperties.getRazorpay().getWebhookSecret();
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException(
                    "Webhook preflight: refusing prod startup with an empty Razorpay webhook secret. "
                            + "The /api/v1/payments/webhooks/razorpay endpoint authenticates provider "
                            + "callbacks with HmacSHA256 over this secret; without it every forged "
                            + "payment.captured settles money. Set RAZORPAY_WEBHOOK_SECRET in the prod overlay.");
        }
        if (DEV_WEBHOOK_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "Webhook preflight: refusing prod startup with the dev-webhook-secret default. "
                            + "A publicly known signing key accepts forged provider webhooks. "
                            + "Set RAZORPAY_WEBHOOK_SECRET to a real secret in the prod overlay.");
        }
        log.info("RAZORPAY_WEBHOOK_PREFLIGHT_OK | secret configured");
    }
}
