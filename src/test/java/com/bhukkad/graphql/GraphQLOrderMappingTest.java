package com.bhukkad.graphql;

import com.bhukkad.dto.response.OrderResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the {@link GraphQLController.Order#from(OrderResponse)} mapping.
 *
 * <p>The GraphQL resolvers wrap the existing REST DTOs in record types so the
 * schema can expose a smaller field set than the REST payload. If this
 * mapping ever drops or mis-translates a field, mobile clients will see
 * silent nulls, so the contract is locked down here.
 */
class GraphQLOrderMappingTest {

    @Test
    void from_copiesAllExposedFields() {
        OrderResponse r = new OrderResponse();
        r.setId(42L);
        r.setOrderNumber("ORD-42");
        r.setStatus("DELIVERED");
        r.setCustomerName("Alice");
        r.setRestaurantName("Spice Hub");
        r.setTotalAmount(550.0);
        r.setSubtotal(500.0);
        r.setDeliveryFee(30.0);
        r.setTaxAmount(20.0);
        r.setTipAmount(0.0);
        r.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0));

        GraphQLController.Order order = GraphQLController.Order.from(r);

        assertEquals(42L, order.id());
        assertEquals("ORD-42", order.orderNumber());
        assertEquals("DELIVERED", order.status());
        assertEquals("Alice", order.customerName());
        assertEquals("Spice Hub", order.restaurantName());
        assertEquals(550.0, order.totalAmount());
        assertEquals(500.0, order.subtotal());
        assertEquals(30.0, order.deliveryFee());
        assertEquals(20.0, order.taxAmount());
        assertEquals(0.0, order.tipAmount());
        assertNotNull(order.createdAt());
    }

    @Test
    void from_handlesNullCreatedAt() {
        OrderResponse r = new OrderResponse();
        r.setId(1L);
        // createdAt intentionally left null
        GraphQLController.Order order = GraphQLController.Order.from(r);
        assertNull(order.createdAt(), "null createdAt must serialise as null, not throw");
    }
}
