package com.bhukkad.restaurant.api.dto.request;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation edge-case tests for {@link RestaurantBusyModeRequest}.
 */
class RestaurantBusyModeRequestValidationTest {

    private static java.util.Set<jakarta.validation.ConstraintViolation<RestaurantBusyModeRequest>> validate(RestaurantBusyModeRequest bean) {
        var factory = jakarta.validation.Validation.buildDefaultValidatorFactory();
        return factory.getValidator().validate(bean);
    }

    private static boolean hasViolationFor(java.util.Set<jakarta.validation.ConstraintViolation<RestaurantBusyModeRequest>> violations, String property) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(property));
    }

    @Test
    void valid_request_passesValidation() {
        var request = new RestaurantBusyModeRequest();
        request.setBusyUntil(LocalDateTime.now().plusHours(1));
        request.setExtraPrepMinutes(15);

        var violations = validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void null_busyUntil_failsValidation() {
        var request = new RestaurantBusyModeRequest();
        request.setExtraPrepMinutes(15);

        var violations = validate(request);
        assertThat(hasViolationFor(violations, "busyUntil")).isTrue();
    }

    @Test
    void past_busyUntil_stillPassesValidation() {
        // Note: We only validate @NotNull here; business logic should enforce future time.
        var request = new RestaurantBusyModeRequest();
        request.setBusyUntil(LocalDateTime.now().minusHours(1));
        request.setExtraPrepMinutes(15);

        var violations = validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void null_extraPrepMinutes_stillPassesValidation() {
        var request = new RestaurantBusyModeRequest();
        request.setBusyUntil(LocalDateTime.now().plusHours(1));

        var violations = validate(request);
        assertThat(violations).isEmpty();
    }
}
