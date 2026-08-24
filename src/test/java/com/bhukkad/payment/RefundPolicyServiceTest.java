package com.bhukkad.payment;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RefundPolicyService}, the cancellation-policy refund
 * eligibility resolver.
 */
class RefundPolicyServiceTest {

    private RefundPolicyService service;

    @BeforeEach
    void setUp() {
        service = new RefundPolicyService();
        service.setEnabled(true);

        Map<String, RefundPolicyService.RefundPolicy> policies = new HashMap<>();
        policies.put("CUSTOMER_CANCELLED",
                new RefundPolicyService.RefundPolicy(100.0, RefundPolicyService.TARGET_WALLET, 30));
        policies.put("RESTAURANT_CANCELLED",
                new RefundPolicyService.RefundPolicy(100.0, RefundPolicyService.TARGET_GATEWAY, 1440));
        policies.put("DELIVERY_FAILED",
                new RefundPolicyService.RefundPolicy(100.0, RefundPolicyService.TARGET_GATEWAY, 1440));
        service.setPolicies(policies);
    }

    private Order orderCreatedMinutesAgo(int minutesAgo) {
        Customer customer = new Customer();
        customer.setId(1L);
        Order order = new Order();
        order.setId(42L);
        order.setOrderNumber("ORD-42");
        order.setCustomer(customer);
        order.setTotalAmount(500.0);
        order.setCreatedAt(LocalDateTime.now().minusMinutes(minutesAgo));
        return order;
    }

    @Test
    void computeRefund_policyMatchedByReason_returnsPolicyWithinWindow() {
        Optional<RefundPolicyService.RefundPolicy> policy =
                service.computeRefund(orderCreatedMinutesAgo(10), "CUSTOMER_CANCELLED");

        assertTrue(policy.isPresent());
        assertEquals(100.0, policy.get().percent());
        assertEquals(RefundPolicyService.TARGET_WALLET, policy.get().target());
        assertEquals(30, policy.get().maxMinutesAfterOrder());
    }

    @Test
    void computeRefund_restaurantCancelled_targetsGateway() {
        Optional<RefundPolicyService.RefundPolicy> policy =
                service.computeRefund(orderCreatedMinutesAgo(60), "RESTAURANT_CANCELLED");

        assertTrue(policy.isPresent());
        assertEquals(RefundPolicyService.TARGET_GATEWAY, policy.get().target());
    }

    @Test
    void computeRefund_windowExpired_returnsEmpty() {
        // CUSTOMER_CANCELLED window is 30 minutes; order is 60 minutes old.
        Optional<RefundPolicyService.RefundPolicy> policy =
                service.computeRefund(orderCreatedMinutesAgo(60), "CUSTOMER_CANCELLED");

        assertTrue(policy.isEmpty());
    }

    @Test
    void computeRefund_unknownReason_returnsEmpty() {
        Optional<RefundPolicyService.RefundPolicy> policy =
                service.computeRefund(orderCreatedMinutesAgo(10), "SOMETHING_ELSE");

        assertTrue(policy.isEmpty());
    }

    @Test
    void computeRefund_disabled_returnsEmptyEvenWhenPolicyWouldApply() {
        service.setEnabled(false);

        Optional<RefundPolicyService.RefundPolicy> policy =
                service.computeRefund(orderCreatedMinutesAgo(10), "CUSTOMER_CANCELLED");

        assertTrue(policy.isEmpty());
    }

    @Test
    void computeRefund_nullOrderTimestamp_returnsEmpty() {
        Order order = orderCreatedMinutesAgo(10);
        order.setCreatedAt(null);

        Optional<RefundPolicyService.RefundPolicy> policy =
                service.computeRefund(order, "CUSTOMER_CANCELLED");

        assertTrue(policy.isEmpty());
    }

    @Test
    void computeRefund_blankReason_returnsEmpty() {
        assertTrue(service.computeRefund(orderCreatedMinutesAgo(10), null).isEmpty());
        assertTrue(service.computeRefund(orderCreatedMinutesAgo(10), "  ").isEmpty());
    }

    @Test
    void computeRefund_nullPercent_returnsEmpty() {
        service.setPolicies(Map.of("BAD_POLICY",
                new RefundPolicyService.RefundPolicy(null, RefundPolicyService.TARGET_WALLET, 30)));

        assertFalse(service.computeRefund(orderCreatedMinutesAgo(10), "BAD_POLICY").isPresent());
    }
}
