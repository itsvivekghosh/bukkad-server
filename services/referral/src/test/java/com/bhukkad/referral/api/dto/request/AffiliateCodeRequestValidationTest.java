package com.bhukkad.referral.api.dto.request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation edge-case tests for {@link AffiliateCodeRequest}.
 */
class AffiliateCodeRequestValidationTest {

    private static java.util.Set<jakarta.validation.ConstraintViolation<AffiliateCodeRequest>> validate(AffiliateCodeRequest bean) {
        var factory = jakarta.validation.Validation.buildDefaultValidatorFactory();
        return factory.getValidator().validate(bean);
    }

    private static boolean hasViolationFor(java.util.Set<jakarta.validation.ConstraintViolation<AffiliateCodeRequest>> violations, String property) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(property));
    }

    @Test
    void valid_request_passesValidation() {
        var request = new AffiliateCodeRequest();
        request.setCode("AFF-100");
        request.setName("Summer Campaign");
        request.setChannel("INSTAGRAM");
        request.setRewardAmount(50.0);
        request.setIsActive(true);

        var violations = validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void blank_code_failsValidation() {
        var request = new AffiliateCodeRequest();
        request.setCode("   ");
        request.setName("Campaign");
        request.setChannel("INSTAGRAM");
        request.setRewardAmount(50.0);
        request.setIsActive(true);

        var violations = validate(request);
        assertThat(hasViolationFor(violations, "code")).isTrue();
    }

    @Test
    void null_code_failsValidation() {
        var request = new AffiliateCodeRequest();
        request.setName("Campaign");
        request.setChannel("INSTAGRAM");
        request.setRewardAmount(50.0);
        request.setIsActive(true);

        var violations = validate(request);
        assertThat(hasViolationFor(violations, "code")).isTrue();
    }

    @Test
    void null_name_failsValidation() {
        var request = new AffiliateCodeRequest();
        request.setCode("AFF-100");
        request.setChannel("INSTAGRAM");
        request.setRewardAmount(50.0);
        request.setIsActive(true);

        var violations = validate(request);
        assertThat(hasViolationFor(violations, "name")).isTrue();
    }

    @Test
    void null_channel_failsValidation() {
        var request = new AffiliateCodeRequest();
        request.setCode("AFF-100");
        request.setName("Campaign");
        request.setRewardAmount(50.0);
        request.setIsActive(true);

        var violations = validate(request);
        assertThat(hasViolationFor(violations, "channel")).isTrue();
    }

    @Test
    void null_rewardAmount_failsValidation() {
        var request = new AffiliateCodeRequest();
        request.setCode("AFF-100");
        request.setName("Campaign");
        request.setChannel("INSTAGRAM");
        request.setIsActive(true);

        var violations = validate(request);
        assertThat(hasViolationFor(violations, "rewardAmount")).isTrue();
    }

    @Test
    void negative_rewardAmount_failsValidation() {
        var request = new AffiliateCodeRequest();
        request.setCode("AFF-100");
        request.setName("Campaign");
        request.setChannel("INSTAGRAM");
        request.setRewardAmount(-10.0);
        request.setIsActive(true);

        var violations = validate(request);
        assertThat(hasViolationFor(violations, "rewardAmount")).isTrue();
    }

    @Test
    void zero_rewardAmount_passesValidation() {
        var request = new AffiliateCodeRequest();
        request.setCode("AFF-100");
        request.setName("Campaign");
        request.setChannel("INSTAGRAM");
        request.setRewardAmount(0.0);
        request.setIsActive(true);

        var violations = validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void null_isActive_failsValidation() {
        var request = new AffiliateCodeRequest();
        request.setCode("AFF-100");
        request.setName("Campaign");
        request.setChannel("INSTAGRAM");
        request.setRewardAmount(50.0);

        var violations = validate(request);
        assertThat(hasViolationFor(violations, "isActive")).isTrue();
    }
}
