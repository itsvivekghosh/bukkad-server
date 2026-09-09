package com.bhukkad.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for Set-Cookie hardening (audit batch A): every cookie that
 * crosses the gateway must come back HttpOnly + Secure + SameSite=Strict,
 * keeping name/value/path/domain/max-age; flows without cookies are a no-op.
 */
class SecureCookieFilterTest {

    private final SecureCookieFilter filter = new SecureCookieFilter();

    @Test
    void hardensWeakCookie_atResponseCommit() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        filter.filter(exchange, ex -> reactor.core.publisher.Mono.empty()).block();

        // Upstream responses land as raw Set-Cookie headers — MockServerHttpResponse
        // buffers addCookie() and writes it at commit AFTER beforeCommit hooks, which
        // the real proxy path never does. Model the header directly.
        String upstream = ResponseCookie.from("SESSION", "abc123")
                .path("/api")
                .domain("bhukkad.com")
                .httpOnly(false)
                .secure(false)
                .sameSite("Lax")
                .maxAge(java.time.Duration.ofHours(2))
                .build()
                .toString();
        exchange.getResponse().getHeaders().add("Set-Cookie", upstream);
        exchange.getResponse().setComplete().block();

        String setCookie = exchange.getResponse().getHeaders().getFirst("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("SESSION=abc123")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/api")
                .contains("Domain=bhukkad.com")
                .contains("Max-Age=7200")
                .doesNotContain("SameSite=Lax");
    }

    @Test
    void hardensMultipleCookies_andSkipsWhenNoCookies() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        filter.filter(exchange, ex -> reactor.core.publisher.Mono.empty()).block();
        // no cookies at all: commit must not synthesize a Set-Cookie header.
        exchange.getResponse().setComplete().block();
        assertThat(exchange.getResponse().getHeaders().containsKey("Set-Cookie")).isFalse();

        MockServerWebExchange twoCookies = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        filter.filter(twoCookies, ex -> reactor.core.publisher.Mono.empty()).block();
        twoCookies.getResponse().getHeaders().add("Set-Cookie", "A=1; Path=/");
        twoCookies.getResponse().getHeaders().add("Set-Cookie", "B=2; SameSite=None; Secure");
        twoCookies.getResponse().setComplete().block();

        var cookies = twoCookies.getResponse().getHeaders().get("Set-Cookie");
        assertThat(cookies).hasSize(2);
        assertThat(cookies).allSatisfy(c -> assertThat(c)
                .contains("HttpOnly").contains("Secure").contains("SameSite=Strict"));
        assertThat(cookies.get(0)).contains("A=1").contains("Path=/");
        assertThat(cookies.get(1)).contains("B=2");
    }

    @Test
    void hardenPreservesEveryOtherAttributeAndDropsOnlyHardenedOnes() {
        String hardened = SecureCookieFilter.harden(
                "sid=xyz; Expires=Wed, 09 Jun 2026 10:18:14 GMT; Unknown=\"x\"; HttpOnly; SameSite=Lax; Secure");
        assertThat(hardened).contains("sid=xyz")
                .contains("Expires=Wed, 09 Jun 2026 10:18:14 GMT")
                .contains("Unknown=\"x\"")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .doesNotContain("SameSite=Lax");
        // only one Secure/HttpOnly instance survives the rewrite
        assertThat(hardened.indexOf("HttpOnly")).isEqualTo(hardened.lastIndexOf("HttpOnly"));
    }
}
