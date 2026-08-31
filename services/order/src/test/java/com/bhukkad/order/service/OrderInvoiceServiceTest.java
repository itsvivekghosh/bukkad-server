package com.bhukkad.order.service;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderInvoice;
import com.bhukkad.order.domain.OrderInvoiceRepository;
import com.bhukkad.order.domain.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderInvoiceServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderInvoiceRepository invoiceRepository;
    @InjectMocks private OrderInvoiceService service;

    private Order order(BigDecimal total) {
        Order order = new Order();
        order.setId(1L);
        order.setTotalAmount(total);
        return order;
    }

    @Test
    void generate_computesGst18() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order(new BigDecimal("100.00"))));
        when(invoiceRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        when(invoiceRepository.save(any(OrderInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderInvoice invoice = service.generate(1L);

        assertThat(invoice.getInvoiceNumber()).isEqualTo("INV-1");
        assertThat(invoice.getGstAmount()).isEqualByComparingTo("18.00");
        assertThat(invoice.getTotal()).isEqualByComparingTo("118.00");
    }

    @Test
    void generate_existingInvoice_isIdempotent() {
        OrderInvoice existing = new OrderInvoice();
        existing.setInvoiceNumber("INV-1");
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order(new BigDecimal("100.00"))));
        when(invoiceRepository.findByOrderId(1L)).thenReturn(Optional.of(existing));

        OrderInvoice invoice = service.generate(1L);

        assertThat(invoice).isSameAs(existing);
    }

    @Test
    void generate_unknownOrder_throws() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.generate(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getByOrder_missingInvoice_throws() {
        when(invoiceRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getByOrder(1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Invoice");
    }
}
