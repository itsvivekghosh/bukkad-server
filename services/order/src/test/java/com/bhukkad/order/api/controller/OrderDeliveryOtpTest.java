package com.bhukkad.order.api.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery OTP hardening contract (replaces invertible String.hashCode):
 * salted SHA-256, distinct per issue, constant-time matcher, legacy-format
 * tokens rejected outright.
 */
class OrderDeliveryOtpTest {

    @Test
    void matchesOwnIssuedOtp() {
        assertThat(OrderAdjunctController.otpMatches(OrderAdjunctController.hashOtp("012345"), "012345"))
                .isTrue();
    }

    @Test
    void rejectsWrongOtp() {
        assertThat(OrderAdjunctController.otpMatches(OrderAdjunctController.hashOtp("012345"), "012346"))
                .isFalse();
    }

    @Test
    void saltedSoSameOtpHashesDifferently() {
        String a = OrderAdjunctController.hashOtp("000001");
        String b = OrderAdjunctController.hashOtp("000001");
        assertThat(a).isNotEqualTo(b);
        assertThat(OrderAdjunctController.otpMatches(a, "000001")).isTrue();
        assertThat(OrderAdjunctController.otpMatches(b, "000001")).isTrue();
    }

    @Test
    void rejectsLegacyHashCodeFormat() {
        // Old rows stored String.valueOf(otp.hashCode()) — no scheme prefix.
        assertThat(OrderAdjunctController.otpMatches("123456789", "2721413")).isFalse();
    }

    @Test
    void rejectsNullAndMalformedStored() {
        assertThat(OrderAdjunctController.otpMatches(null, "111111")).isFalse();
        assertThat(OrderAdjunctController.otpMatches("$sha256$only", "111111")).isFalse();
    }

    @Test
    void hashOutputIs64HexChars() {
        String h = OrderAdjunctController.sha256Hex("x");
        assertThat(h).hasSize(64).matches("[0-9a-f]+");
    }
}
