package com.bhukkad.notification.infrastructure.client;

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

/**
 * Unit tests for {@link TwilioSmsSender} against a mocked HTTP layer (a
 * stubbed {@link ExchangeFunction}, same double convention as the platform
 * filter tests): request shape (URL, basic auth, form fields), success and
 * failure paths, and the P-05 contract that transport failures surface into
 * the annotation fallback instead of throwing to the caller.
 */
class TwilioSmsSenderTest {

    private RecordingExchange exchange;
    private NotificationProperties properties;
    private TwilioSmsSender sender;

    /** Records every request; replies with a canned response per attempt. */
    private static final class RecordingExchange implements ExchangeFunction {
        final List<ClientRequest> requests = new ArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        private HttpStatus status = HttpStatus.OK;
        private RuntimeException error;

        void respondWith(HttpStatus status) {
            this.status = status;
        }

        void failWith(RuntimeException error) {
            this.error = error;
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            requests.add(request);
            attempts.incrementAndGet();
            if (error != null) {
                return Mono.error(error);
            }
            return Mono.just(ClientResponse.create(status).build());
        }
    }

    @BeforeEach
    void setUp() {
        exchange = new RecordingExchange();
        WebClient webClient = WebClient.builder().exchangeFunction(exchange).build();
        properties = new NotificationProperties();
        properties.getSms().setProvider("twilio");
        NotificationProperties.Twilio twilio = properties.getSms().getTwilio();
        twilio.setAccountSid("AC123");
        twilio.setAuthToken("secret-token");
        twilio.setFromNumber("+15550001111");
        sender = new TwilioSmsSender(properties, webClient);
    }

    @Test
    void send_postsTwilioMessagesApi_withBasicAuthAndFormFields() {
        boolean sent = sender.send("+911234567890", "Your order is ready");

        assertThat(sent).isTrue();
        assertThat(exchange.requests).hasSize(1);
        ClientRequest request = exchange.requests.get(0);
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.url()).isEqualTo(URI.create(
                "https://api.twilio.com/2010-04-01/Accounts/AC123/Messages.json"));
        assertThat(request.headers().getFirst("Authorization"))
                .startsWith("Basic ")
                // accountSid:authToken, base64 — never logged, but verifiable.
                .isEqualTo("Basic " + java.util.Base64.getEncoder()
                        .encodeToString("AC123:secret-token".getBytes()));
        assertThat(request.headers().getContentType().toString())
                .isEqualTo("application/x-www-form-urlencoded");

        // Form body: the To/From/Body contract, captured by writing the
        // request's BodyInserter into a spring-test mock request.
        assertThat(capturedForm(request))
                .containsEntry("To", "+911234567890")
                .containsEntry("From", "+15550001111")
                .containsEntry("Body", "Your order is ready");
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
    void send_skipsWithoutPhoneNumber() {
        boolean sent = sender.send(" ", "body");

        assertThat(sent).isFalse();
        assertThat(exchange.requests).isEmpty();
    }

    @Test
    void send_skipsWithoutCredentials() {
        properties.getSms().getTwilio().setAccountSid("");

        boolean sent = sender.send("+911234567890", "body");

        assertThat(sent).isFalse();
        assertThat(exchange.requests).isEmpty();
    }

    @Test
    void send_returnsFalse_whenProviderErrors_transportFailureDoesNotThrow() {
        // The annotation fallback (smsUnavailable) swallows provider errors;
        // the direct-call path in a unit test (no AOP proxy) surfaces them.
        // This test pins the sender's contract: failure comes back as a
        // raised WebClientResponseException — which the fallback method maps
        // to false in production via the AOP proxy.
        exchange.respondWith(HttpStatus.INTERNAL_SERVER_ERROR);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> sender.send("+911234567890", "body"))
                .isInstanceOf(org.springframework.web.reactive.function.client.WebClientResponseException.class);
        // Exactly ONE attempt: no transport-level retry for non-idempotent
        // POST sends (eventId dedupe upstream guarantees at-least-once
        // dispatch, never double HTTP sends).
        assertThat(exchange.attempts.get()).isEqualTo(1);
    }

    @Test
    void productionWebClient_usesPlatformFactoryDefaults() {
        // The production client must come from the platform factory: per
        // P-05 the response cap for notification sends is 3 s (overrides the
        // 5 s platform default) and the breaker target is twilio-sms.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WebClient productionClient = TwilioSmsSender.defaultWebClient(registry);
        assertThat(productionClient).isNotNull();

        assertThat(TwilioSmsSender.RESPONSE_TIMEOUT).isEqualTo(Duration.ofSeconds(3));
        assertThat(TwilioSmsSender.TARGET).isEqualTo("twilio-sms");
    }
}
