package com.bhukkad.invoice;

import com.bhukkad.entity.Address;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderInvoice;
import com.bhukkad.entity.OrderItem;
import com.bhukkad.entity.Restaurant;
import com.lowagie.text.Document;
import com.lowagie.text.pdf.PdfWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class InvoicePdfRendererTest {

    private final InvoicePdfRenderer renderer = new InvoicePdfRenderer();

    @Test
    void render_returnsValidPdf() {
        OrderInvoice invoice = createFullInvoice();
        byte[] pdf = renderer.render(invoice);
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        String header = new String(pdf, 0, Math.min(4, pdf.length));
        assertTrue(header.startsWith("%PDF"));
    }

    @Test
    void render_withNullOrder_stillProducesPdf() {
        OrderInvoice invoice = createFullInvoice();
        invoice.setOrder(null);
        byte[] pdf = renderer.render(invoice);
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        String header = new String(pdf, 0, Math.min(4, pdf.length));
        assertTrue(header.startsWith("%PDF"));
    }

    @Test
    void render_withNullRestaurant_stillProducesPdf() {
        Order order = createOrder();
        order.setRestaurant(null);
        OrderInvoice invoice = createFullInvoice();
        invoice.setOrder(order);
        byte[] pdf = renderer.render(invoice);
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        String header = new String(pdf, 0, Math.min(4, pdf.length));
        assertTrue(header.startsWith("%PDF"));
    }

    @Test
    void render_withNullRestaurantAddress_stillProducesPdf() {
        Order order = createOrder();
        order.getRestaurant().setAddress(null);
        OrderInvoice invoice = createFullInvoice();
        invoice.setOrder(order);
        byte[] pdf = renderer.render(invoice);
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        assertTrue(new String(pdf, 0, 4).startsWith("%PDF"));
    }

    @Test
    void render_withNullDeliveryAddress_stillProducesPdf() {
        Order order = createOrder();
        order.setDeliveryAddress(null);
        OrderInvoice invoice = createFullInvoice();
        invoice.setOrder(order);
        byte[] pdf = renderer.render(invoice);
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        assertTrue(new String(pdf, 0, 4).startsWith("%PDF"));
    }

    @Test
    void render_withNullCustomer_stillProducesPdf() {
        Order order = createOrder();
        order.setCustomer(null);
        OrderInvoice invoice = createFullInvoice();
        invoice.setOrder(order);
        byte[] pdf = renderer.render(invoice);
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        assertTrue(new String(pdf, 0, 4).startsWith("%PDF"));
    }

    @Test
    void render_withNullInvoiceFields_stillProducesPdf() {
        OrderInvoice invoice = createFullInvoice();
        invoice.setInvoiceNumber(null);
        invoice.setIssuedAt(null);
        invoice.setRestaurantGstin(null);
        invoice.setSubtotal(null);
        invoice.setDeliveryFee(null);
        invoice.setDiscountAmount(null);
        invoice.setCgstAmount(null);
        invoice.setSgstAmount(null);
        invoice.setTotalAmount(null);
        byte[] pdf = renderer.render(invoice);
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        assertTrue(new String(pdf, 0, 4).startsWith("%PDF"));
    }

    @Test
    void render_withEmptyOrderItems_stillProducesPdf() {
        Order order = createOrder();
        order.setOrderItems(List.of());
        OrderInvoice invoice = createFullInvoice();
        invoice.setOrder(order);
        byte[] pdf = renderer.render(invoice);
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        assertTrue(new String(pdf, 0, 4).startsWith("%PDF"));
    }

    @Test
    void render_withNullOrderItemsList_stillProducesPdf() {
        Order order = createOrder();
        order.setOrderItems(null);
        OrderInvoice invoice = createFullInvoice();
        invoice.setOrder(order);
        byte[] pdf = renderer.render(invoice);
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        assertTrue(new String(pdf, 0, 4).startsWith("%PDF"));
    }

    @Test
    void render_withNullItemFields_stillProducesPdf() {
        OrderItem item = new OrderItem();
        item.setQuantity(null);
        item.setPrice(null);
        item.setMenuItem(null);
        Order order = createOrder();
        order.setOrderItems(List.of(item));
        OrderInvoice invoice = createFullInvoice();
        invoice.setOrder(order);
        byte[] pdf = renderer.render(invoice);
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        assertTrue(new String(pdf, 0, 4).startsWith("%PDF"));
    }

    @Test
    void render_nullInvoice_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> renderer.render(null));
    }

    @Test
    void render_whenPdfWriterFails_throwsIllegalStateException() {
        OrderInvoice invoice = createFullInvoice();
        try (MockedStatic<PdfWriter> writer = mockStatic(PdfWriter.class)) {
            writer.when(() -> PdfWriter.getInstance(any(Document.class), any(OutputStream.class)))
                    .thenThrow(new com.lowagie.text.DocumentException("boom"));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> renderer.render(invoice));

            assertTrue(ex.getMessage().contains("Unable to render invoice PDF"));
            assertNotNull(ex.getCause());
        }
    }

    private OrderInvoice createFullInvoice() {
        Order order = createOrder();
        OrderInvoice invoice = new OrderInvoice();
        invoice.setId(1L);
        invoice.setOrder(order);
        invoice.setInvoiceNumber("INV-001");
        invoice.setSubtotal(500.0);
        invoice.setDeliveryFee(30.0);
        invoice.setTaxAmount(45.0);
        invoice.setCgstAmount(22.5);
        invoice.setSgstAmount(22.5);
        invoice.setDiscountAmount(50.0);
        invoice.setTotalAmount(525.0);
        invoice.setRestaurantGstin("29ABCDE1234F1Z5");
        invoice.setIssuedAt(LocalDateTime.of(2025, 1, 15, 14, 30));
        return invoice;
    }

    private Order createOrder() {
        Customer customer = new Customer();
        customer.setId(1L);
        customer.setFullName("Amit Sharma");

        Restaurant restaurant = new Restaurant();
        restaurant.setId(10L);
        restaurant.setName("Punjab Dhaba");

        Address restaurantAddress = new Address();
        restaurantAddress.setId(100L);
        restaurantAddress.setAddressLine1("MG Road");
        restaurantAddress.setCity("Bangalore");
        restaurantAddress.setState("Karnataka");
        restaurantAddress.setPincode("560001");
        restaurant.setAddress(restaurantAddress);

        Address deliveryAddress = new Address();
        deliveryAddress.setId(101L);
        deliveryAddress.setAddressLine1("Indiranagar");
        deliveryAddress.setAddressLine2("Stage 2");
        deliveryAddress.setCity("Bangalore");
        deliveryAddress.setState("Karnataka");
        deliveryAddress.setPincode("560038");

        MenuItem menuItem = new MenuItem();
        menuItem.setId(20L);
        menuItem.setName("Butter Chicken");

        OrderItem orderItem = new OrderItem();
        orderItem.setId(1L);
        orderItem.setMenuItem(menuItem);
        orderItem.setQuantity(2);
        orderItem.setPrice(250.0);

        Order order = new Order();
        order.setId(5L);
        order.setOrderNumber("ORD-20250115-001");
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setDeliveryAddress(deliveryAddress);
        order.setOrderItems(List.of(orderItem));
        return order;
    }
}