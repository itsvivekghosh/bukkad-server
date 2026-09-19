package com.bhukkad.order.api.dto.request;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation edge-case tests for {@link CreateOrderRequest} and {@link OrderItemRequest}.
 *
 * <p>These tests verify that Jakarta Validation constraints are declared on
 * request DTOs so the controller-level {@code @Valid} can reject malformed
 * payloads before any business logic runs.</p>
 */
class OrderRequestValidationTest {

    private static String leafPropertyName(jakarta.validation.ConstraintViolation<?> v) {
        var path = v.getPropertyPath();
        var iterator = path.iterator();
        String leaf = null;
        while (iterator.hasNext()) {
            leaf = iterator.next().getName();
        }
        return leaf;
    }

    private static java.util.Set<jakarta.validation.ConstraintViolation<Object>> validate(Object bean) {
        var factory = jakarta.validation.Validation.buildDefaultValidatorFactory();
        var validator = factory.getValidator();
        return validator.validate(bean);
    }

    private static boolean hasViolationFor(java.util.Set<jakarta.validation.ConstraintViolation<Object>> violations, String property) {
        return violations.stream().anyMatch(v -> property.equals(leafPropertyName(v)));
    }

    // ---- CreateOrderRequest ----

    @Test
    void valid_request_passesValidation() {
        var item = new OrderItemRequest(1L, "Pizza", new BigDecimal("9.99"), 2);
        var request = new CreateOrderRequest(7L, 1L, List.of(item));

        var violations = validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void null_customerId_failsValidation() {
        var request = new CreateOrderRequest(null, 1L, List.of(
                new OrderItemRequest(1L, "Pizza", new BigDecimal("9.99"), 2)));

        var violations = validate(request);
        assertThat(hasViolationFor(violations, "customerId")).isTrue();
    }

    @Test
    void null_restaurantId_failsValidation() {
        var request = new CreateOrderRequest(7L, null, List.of(
                new OrderItemRequest(1L, "Pizza", new BigDecimal("9.99"), 2)));

        var violations = validate(request);
        assertThat(hasViolationFor(violations, "restaurantId")).isTrue();
    }

    @Test
    void negative_restaurantId_failsValidation() {
        var request = new CreateOrderRequest(7L, -1L, List.of(
                new OrderItemRequest(1L, "Pizza", new BigDecimal("9.99"), 2)));

        var violations = validate(request);
        assertThat(hasViolationFor(violations, "restaurantId")).isTrue();
    }

    @Test
    void empty_items_failsValidation() {
        var request = new CreateOrderRequest(7L, 1L, List.of());

        var violations = validate(request);
        assertThat(hasViolationFor(violations, "items")).isTrue();
    }

    @Test
    void null_items_failsValidation() {
        var request = new CreateOrderRequest(7L, 1L, null);

        var violations = validate(request);
        assertThat(hasViolationFor(violations, "items")).isTrue();
    }

    // ---- OrderItemRequest ----

    @Test
    void valid_orderItem_passesValidation() {
        var item = new OrderItemRequest(1L, "Pizza", new BigDecimal("9.99"), 2);

        var violations = validate(item);
        assertThat(violations).isEmpty();
    }

    @Test
    void null_menuItemId_failsValidation() {
        var item = new OrderItemRequest(null, "Pizza", new BigDecimal("9.99"), 2);

        var violations = validate(item);
        assertThat(hasViolationFor(violations, "menuItemId")).isTrue();
    }

    @Test
    void negative_menuItemId_failsValidation() {
        var item = new OrderItemRequest(-1L, "Pizza", new BigDecimal("9.99"), 2);

        var violations = validate(item);
        assertThat(hasViolationFor(violations, "menuItemId")).isTrue();
    }

    @Test
    void blank_name_failsValidation() {
        var item = new OrderItemRequest(1L, "  ", new BigDecimal("9.99"), 2);

        var violations = validate(item);
        assertThat(hasViolationFor(violations, "name")).isTrue();
    }

    @Test
    void null_unitPrice_failsValidation() {
        var item = new OrderItemRequest(1L, "Pizza", null, 2);

        var violations = validate(item);
        assertThat(hasViolationFor(violations, "unitPrice")).isTrue();
    }

    @Test
    void negative_unitPrice_failsValidation() {
        var item = new OrderItemRequest(1L, "Pizza", new BigDecimal("-1.00"), 2);

        var violations = validate(item);
        assertThat(hasViolationFor(violations, "unitPrice")).isTrue();
    }

    @Test
    void zero_quantity_failsValidation() {
        var item = new OrderItemRequest(1L, "Pizza", new BigDecimal("9.99"), 0);

        var violations = validate(item);
        assertThat(hasViolationFor(violations, "quantity")).isTrue();
    }

    @Test
    void negative_quantity_failsValidation() {
        var item = new OrderItemRequest(1L, "Pizza", new BigDecimal("9.99"), -1);

        var violations = validate(item);
        assertThat(hasViolationFor(violations, "quantity")).isTrue();
    }

    @Test
    void null_quantity_failsValidation() {
        var item = new OrderItemRequest(1L, "Pizza", new BigDecimal("9.99"), null);

        var violations = validate(item);
        assertThat(hasViolationFor(violations, "quantity")).isTrue();
    }
}
