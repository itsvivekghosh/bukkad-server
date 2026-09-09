package com.bhukkad.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** V-21: masking keeps routing-relevant edges, never the raw value. */
class LogRedactorTest {

    @Test
    void maskE164_keepsFirstTwoAndLastTwo() {
        assertThat(LogRedactor.maskE164("+919812345678"))
                .isEqualTo("+9*********78")
                .doesNotContain("9812345");
    }

    @Test
    void maskE164_shortValuesFullyMasked() {
        assertThat(LogRedactor.maskE164("1234")).isEqualTo("****");
        assertThat(LogRedactor.maskE164(null)).isNull();
    }

    @Test
    void maskE164_rawDigitsNeverAppear() {
        String phone = "+919876543210";
        String masked = LogRedactor.maskE164(phone);
        assertThat(masked).doesNotContain("1987654");
        assertThat(masked).startsWith("+9").endsWith("10");
    }

    @Test
    void maskEmail_keepsFirstCharAndTldEdge() {
        assertThat(LogRedactor.maskEmail("vivek.ghosh@example.com"))
                .isEqualTo("v**********@e*********m");
    }

    @Test
    void maskEmail_nonEmailFullyMasked() {
        assertThat(LogRedactor.maskEmail("garbage")).isEqualTo("*******");
        assertThat(LogRedactor.maskEmail(null)).isNull();
        assertThat(LogRedactor.maskEmail("")).isEmpty();
    }

    @Test
    void maskEmail_rawLocalAndDomainNeverAppear() {
        String email = "secret.person@corp-domain.in";
        String masked = LogRedactor.maskEmail(email);
        assertThat(masked).doesNotContain("secret").doesNotContain("corp-domain");
    }
}
