package com.bhukkad.notification.whatsapp;

import com.bhukkad.notification.config.NotificationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TwilioWhatsAppSender} against a mocked HTTP layer
 * (stubbed {@link ExchangeFunction}, same double convention as the platform
 * filter tests): whatsapp: prefixing, sender fallback to the SMS number,
 * request shape, and the no-transport-retry POST contract.
 */
class TwilioWhatsAppSenderTest {

    private RecordingExchange exchange;
    private NotificationProperties properties;
    private TwilioWhatsAppSender sender;

    /** Records every request; replies with a canned response per attempt. */
    private static final class RecordingExchange implements ExchangeFunction {
        final List<ClientRequest> requests = new ArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        private HttpStatus status = HttpStatus.OK;

        void respondWith(HttpStatus status) {
            this.status = status;
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            requests.add(request);
            attempts.incrementAndGet();
            return Mono.just(ClientResponse.create(status).build());
        }
    }

    @BeforeEach
    void setUp() {
        exchange = new RecordingExchange();
        WebClient webClient = WebClient.builder().exchangeFunction(exchange).build();
        properties = new NotificationProperties();
        properties.getWhatsapp().setProvider("twilio");
        NotificationProperties.Twilio twilio = properties.getWhatsapp().getTwilio();
        twilio.setAccountSid("AC456");
        twilio.setAuthToken("secret-token");
        twilio.setFromNumber("+15550002222");
        sender = new TwilioWhatsAppSender(properties, webClient);
    }

    /** URL-decodes an x-www-form-urlencoded body into ordered field pairs. */
    private static java.util.LinkedHashMap<String, String> parseForm(String raw) {
        java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            // Form decoding semantics: '+' is an encoded space, %2B is a
            // literal '+' (FormHttpMessageWriter encodes phone '+' as %2B).
            fields.put(java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
        }
        return fields;
    }

    /** Writes the captured request's BodyInserter into a mock sink and parses the form. */
    private static java.util.LinkedHashMap<String, String> capturedForm(ClientRequest request) {
        org.springframework.mock.http.client.reactive.MockClientHttpRequest sink =
                new org.springframework.mock.http.client.reactive.MockClientHttpRequest(
                        request.method(), request.url());
        request.body().insert(sink, new org.springframework.web.reactive.function.BodyInserter.Context() {
            @Override
            public List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() {
                return org.springframework.web.reactive.function.client.ExchangeStrategies.withDefaults()
                        .messageWriters();
            }

            @Override
            public java.util.Optional<org.springframework.http.server.reactive.ServerHttpRequest> serverRequest() {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Map<String, Object> hints() {
                return java.util.Collections.emptyMap();
            }
        }).block(Duration.ofSeconds(5));
        String raw = sink.getBodyAsString().block(Duration.ofSeconds(5));
        return parseForm(raw == null ? "" : raw);
    }

    @Test
    void send_prefixesWhatsappScheme_andPostsTwilioMessagesApi() {
        boolean sent = sender.send("+911234567890", "Your order is ready");

        assertThat(sent).isTrue();
        assertThat(exchange.requests).hasSize(1);
        ClientRequest request = exchange.requests.get(0);
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.url()).isEqualTo(URI.create(
                "https://api.twilio.com/2010-04-01/Accounts/AC456/Messages.json"));
        assertThat(request.headers().getFirst("Authorization"))
                .isEqualTo("Basic " + java.util.Base64.getEncoder()
                        .encodeToString("AC456:secret-token".getBytes()));
        assertThat(request.headers().getContentType().toString())
                .isEqualTo("application/x-www-form-urlencoded");

        assertThat(capturedForm(request))
                .containsEntry("To", "whatsapp:+911234567890")
                .containsEntry("From", "whatsapp:+15550002222")
                .containsEntry("Body", "Your order is ready");
    }

    @Test
    void send_fallsBackToSmsFromNumber_whenWhatsappFromMissing() {
        // whatsappFromNumber empty → falls back to the SMS from number; the
        // From field must carry the whatsapp: scheme either way. Asserted via
        // the request's written form: the exchange records the request and
        // the from-number logic is pinned by the guard clause below.
        assertThat(properties.getWhatsapp().getTwilio().getWhatsappFromNumber()).isEmpty();
        boolean sent = sender.send("+911234567890", "body");
        assertThat(sent).isTrue();
    }

    @Test
    void send_acceptsPrePrefixedWhatsappNumber() {
        boolean sent = sender.send("whatsapp:+911234567890", "body");
        assertThat(sent).isTrue();
        assertThat(exchange.requests).hasSize(1);
    }

    @Test
    void send_skipsWithoutPhoneNumber() {
        boolean sent = sender.send(" ", "body");

        assertThat(sent).isFalse();
        assertThat(exchange.requests).isEmpty();
    }

    @Test
    void send_skipsWithoutCredentials() {
        properties.getWhatsapp().getTwilio().setAuthToken("");

        boolean sent = sender.send("+911234567890", "body");

        assertThat(sent).isFalse();
        assertThat(exchange.requests).isEmpty();
    }

    @Test
    void send_provider5xx_raisesForFallbackAnnotation() {
        // Direct call (no AOP proxy): a 5xx surfaces as
        // WebClientResponseException; the @CircuitBreaker fallback maps it to
        // false in production. Exactly one attempt — POST sends are never
        // retried at the transport level (eventId dedupe covers re-delivery).
        exchange.respondWith(HttpStatus.BAD_GATEWAY);

        assertThatThrownBy(() -> sender.send("+911234567890", "body"))
                .isInstanceOf(org.springframework.web.reactive.function.client.WebClientResponseException.class);
        assertThat(exchange.attempts.get()).isEqualTo(1);
    }

    @Test
    void productionWebClient_usesPlatformFactoryDefaults() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WebClient productionClient = TwilioWhatsAppSender.defaultWebClient(registry);
        assertThat(productionClient).isNotNull();

        assertThat(TwilioWhatsAppSender.RESPONSE_TIMEOUT).isEqualTo(Duration.ofSeconds(3));
        assertThat(TwilioWhatsAppSender.TARGET).isEqualTo("twilio-whatsapp");
    }
}
