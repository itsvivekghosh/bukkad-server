package com.bhukkad.notification.whatsapp;

import com.bhukkad.common.util.LogRedactor;
import com.bhukkad.common.web.client.PlatformWebClientBuilderFactory;
import com.bhukkad.notification.config.NotificationProperties;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Twilio WhatsApp sender on the platform WebClient factory (P-05).
 *
 * <p>The HTTP client comes from {@link PlatformWebClientBuilderFactory}: the
 * bounded JVM-wide connection pool, 2 s connect timeout, 3 s response timeout
 * (notifications must fail fast into the fallback instead of pinning dispatch
 * workers), and a circuit breaker named per target ({@code twilio-whatsapp})
 * alongside the {@code notificationWhatsApp} annotation breaker below.</p>
 *
 * <p>Retry policy: NONE at the transport level. A Twilio Messages POST is not
 * idempotent — retrying after a lost response could double-send — and
 * at-least-once re-delivery is already deduplicated upstream by the
 * eventId claim ({@code KAFKA_CONSUME} idempotency record, P-07), so a
 * repeated HTTP send is never issued for the same event.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.whatsapp.provider", havingValue = "twilio")
public class TwilioWhatsAppSender implements WhatsAppSender {

    /** Breaker/metric target name — one breaker for all WhatsApp sends. */
    static final String TARGET = "twilio-whatsapp";
    /** Notifications fail fast: response cap 3 s (2-3 s window per P-05). */
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    private final NotificationProperties notificationProperties;
    private final WebClient webClient;

    public TwilioWhatsAppSender(NotificationProperties notificationProperties,
                                ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(notificationProperties, defaultWebClient(meterRegistryProvider.getIfAvailable()));
    }

    /** Test seam: inject a client whose exchange function is stubbed. */
    TwilioWhatsAppSender(NotificationProperties notificationProperties, WebClient webClient) {
        this.notificationProperties = notificationProperties;
        this.webClient = webClient;
    }

    /** The platform-built production client (timeouts/pool/breaker per P-05). */
    static WebClient defaultWebClient(MeterRegistry meterRegistry) {
        return PlatformWebClientBuilderFactory.forTarget(TARGET, meterRegistry)
                .responseTimeout(RESPONSE_TIMEOUT)
                .build();
    }

    @Override
    @CircuitBreaker(name = "notificationWhatsApp", fallbackMethod = "whatsAppUnavailable")
    @Bulkhead(name = "notificationWhatsApp", fallbackMethod = "whatsAppUnavailable")
    public boolean send(String phoneNumber, String body) {
        if (!StringUtils.hasText(phoneNumber)) {
            return false;
        }
        NotificationProperties.Twilio twilio = notificationProperties.getWhatsapp().getTwilio();
        String from = StringUtils.hasText(twilio.getWhatsappFromNumber())
                ? twilio.getWhatsappFromNumber()
                : twilio.getFromNumber();
        if (!StringUtils.hasText(twilio.getAccountSid())
                || !StringUtils.hasText(twilio.getAuthToken())
                || !StringUtils.hasText(from)) {
            log.warn("Twilio WhatsApp credentials not configured");
            return false;
        }

        String to = phoneNumber.startsWith("whatsapp:") ? phoneNumber : "whatsapp:" + phoneNumber;
        String fromAddr = from.startsWith("whatsapp:") ? from : "whatsapp:" + from;
        String url = "https://api.twilio.com/2010-04-01/Accounts/" + twilio.getAccountSid() + "/Messages.json";

        webClient.post()
                .uri(url)
                .headers(headers -> headers.setBasicAuth(twilio.getAccountSid(), twilio.getAuthToken()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("To", to)
                        .with("From", fromAddr)
                        .with("Body", body))
                .retrieve()
                .toBodilessEntity()
                .block(RESPONSE_TIMEOUT);
        log.info("Twilio WhatsApp sent | to={}", LogRedactor.maskE164(phoneNumber));
        return true;
    }

    public boolean whatsAppUnavailable(String phoneNumber, String body, Throwable ex) {
        log.warn("Twilio WhatsApp unavailable (circuit open) | to={} | error={}",
                LogRedactor.maskE164(phoneNumber), ex.getMessage());
        return false;
    }
}
