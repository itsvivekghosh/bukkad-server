package com.bhukkad.order.api;

import com.bhukkad.order.domain.Cart;
import com.bhukkad.order.domain.CartItem;
import com.bhukkad.order.domain.OrderInvoice;
import com.bhukkad.order.service.CartService;
import com.bhukkad.order.service.OrderEtaService;
import com.bhukkad.order.service.OrderInvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cart + invoice + ETA APIs (Batch B depth).
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final OrderInvoiceService invoiceService;
    private final OrderEtaService etaService;

    public record AddItemRequest(Long menuItemId, String name, BigDecimal unitPrice, int quantity) {}

    @PostMapping("/cart/items")
    public Cart addItem(@PathVariable Long customerId, @RequestBody AddItemRequest request) {
        return cartService.addItem(customerId, request.menuItemId(), request.name(),
                request.unitPrice(), request.quantity());
    }

    @GetMapping("/cart")
    public List<CartItem> getCart(@PathVariable Long customerId) {
        return cartService.getItems(customerId);
    }

    @GetMapping("/cart/subtotal")
    public BigDecimal subtotal(@PathVariable Long customerId) {
        return cartService.subtotal(customerId);
    }

    @DeleteMapping("/cart")
    public void clearCart(@PathVariable Long customerId) {
        cartService.clear(customerId);
    }

    @GetMapping("/orders/{orderId}/invoice")
    public OrderInvoice invoice(@PathVariable Long orderId) {
        return invoiceService.getByOrder(orderId);
    }
}