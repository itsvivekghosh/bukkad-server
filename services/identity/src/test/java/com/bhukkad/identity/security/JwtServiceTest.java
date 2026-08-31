package com.bhukkad.identity.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 32 bytes

    private JwtService service(long ttlMinutes) {
        return new JwtService(new JwtProperties(SECRET, ttlMinutes));
    }

    @Test
    void issueAndVerify_roundTripsCustomerId() {
        JwtService svc = service(60);
        String token = svc.issue(42L, "a@b.com");

        assertThat(svc.verifyAndGetCustomerId(token)).isEqualTo(42L);
    }

    @Test
    void verify_tamperedToken_rejected() {
        JwtService svc = service(60);
        String token = svc.issue(42L, "a@b.com");
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThatThrownBy(() -> svc.verifyAndGetCustomerId(tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verify_garbageToken_rejected() {
        JwtService svc = service(60);
        assertThatThrownBy(() -> svc.verifyAndGetCustomerId("not-a-jwt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verify_emptyToken_rejected() {
        JwtService svc = service(60);
        assertThatThrownBy(() -> svc.verifyAndGetCustomerId(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void properties_rejectShortSecret() {
        assertThatThrownBy(() -> new JwtProperties("short", 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");
    }

    @Test
    void properties_rejectNonPositiveTtl() {
        assertThatThrownBy(() -> new JwtProperties(SECRET, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }
}
