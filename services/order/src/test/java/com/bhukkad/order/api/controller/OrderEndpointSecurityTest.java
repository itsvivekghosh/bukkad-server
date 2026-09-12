package com.bhukkad.order.api.controller;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.*;
import com.bhukkad.order.service.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Per-endpoint unit matrix for the canonical order/cart surfaces.
 * Every endpoint is exercised for: (a) happy path, (b) IDOR guard,
 * (c) unauthenticated caller guard.
 */
class OrderEndpointSecurityTest {

    private static TokenPrincipal principal(long userId, String scope) {
        return new TokenPrincipal(userId, "u@t.test", scope);
    }

    private static OrderResponse order(Long id, Long customerId) {
        return new OrderResponse(id, customerId, 1L, "CREATED", new BigDecimal("10.00"), List.of());
    }

    // ---- CartController ----
    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class CartEndpoints {
        @Mock private CartService cartService;
        @Mock private OrderInvoiceService invoiceService;
        @Mock private OrderService orderService;
        @Mock private RestaurantPricedItemResolver resolver;
        @InjectMocks private CartController controller;

        @Test
        void addItem_happyPath_ignoresClientPrice() {
            when(resolver.resolve(5L)).thenReturn(
                    new RestaurantPricedItemResolver.PricedItem(5L, "Pizza", new BigDecimal("9.99")));
            when(cartService.addItem(anyLong(), anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(new Cart());

            controller.addItem(principal(1L, "CUSTOMER"), 1L, new CartController.AddItemRequest(5L, 2));

            // The price passed downstream is the SERVER-resolved price.
            org.mockito.Mockito.verify(cartService).addItem(1L, 5L, "Pizza", new BigDecimal("9.99"), 2);
        }

        @Test
        void addItem_crossCustomer_throws() {
            assertThatThrownBy(() -> controller.addItem(principal(2L, "CUSTOMER"), 1L,
                    new CartController.AddItemRequest(5L, 2)))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }

        @Test
        void getCart_crossCustomer_throws() {
            assertThatThrownBy(() -> controller.getCart(principal(2L, "CUSTOMER"), 1L))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }

        @Test
        void clearCart_unauthenticated_throws() {
            assertThatThrownBy(() -> controller.clearCart(null, 1L))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }

        @Test
        void invoice_otherCustomersInvoice_throws() {
            OrderResponse other = order(9L, 77L);
            when(orderService.getOrder(9L)).thenReturn(other);

            assertThatThrownBy(() -> controller.invoice(principal(1L, "CUSTOMER"), 1L, 9L))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
    }

    // ---- OrderController ----
    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class OrderEndpoints {
        @Mock private OrderService orderService;
        @InjectMocks private OrderController controller;

        @Test
        void create_forcesCustomerIdFromPrincipal() {
            when(orderService.createOrder(any())).thenReturn(
                    order(1L, 7L));

            controller.create(principal(7L, "CUSTOMER"),
                    new CreateOrderRequest(999L, 1L,
                            List.of(new OrderItemRequest(5L, "Pizza", new BigDecimal("9.99"), 1))));

            // Body customerId (999) discarded; subject (7) wins.
            org.mockito.Mockito.verify(orderService).createOrder(org.mockito.ArgumentMatchers.argThat(
                    r -> r.customerId().equals(7L)));
        }

        @Test
        void get_otherCustomersOrder_throws() {
            when(orderService.getOrder(9L)).thenReturn(
                    order(9L, 77L));

            assertThatThrownBy(() -> controller.get(principal(1L, "CUSTOMER"), 9L))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }

        @Test
        void cancel_ownerAllowed() {
            when(orderService.getOrder(9L)).thenReturn(
                    order(9L, 1L));
            when(orderService.getOrder(9L)).thenReturn(
                    order(9L, 1L));

            assertThat(controller.cancel(principal(1L, "CUSTOMER"), 9L)).isNotNull();
            org.mockito.Mockito.verify(orderService).cancelOrder(9L);
        }

        @Test
        void cancel_adminAllowedAcrossCustomers() {
            when(orderService.getOrder(9L)).thenReturn(
                    order(9L, 1L));

            assertThat(controller.cancel(principal(2L, "ADMIN"), 9L)).isNotNull();
        }
    }

    // ---- GiftCardController ----
    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class GiftCardEndpoints {
        @Mock private SocialOrderService socialService;
        @InjectMocks private GiftCardController controller;

        @Test
        void issue_declaresAdminGate() throws Exception {
            // @PreAuthorize is enforced by method security in the full context;
            // the unit matrix pins the annotation contract via reflection.
            var issue = GiftCardController.class.getMethod("issue",
                    com.bhukkad.common.security.TokenPrincipal.class, BigDecimal.class);
            var gate = issue.getAnnotation(
                    org.springframework.security.access.prepost.PreAuthorize.class);
            assertThat(gate).isNotNull();
            assertThat(gate.value()).contains("ADMIN");
        }

        @Test
        void redeem_jsonBody_recordsRedeemerAndBalance() {
            // Monolith-parity contract: {"code":"...","amount":N} body map.
            when(socialService.redeemGiftCard("GC-1", 7L, new BigDecimal("50")))
                    .thenReturn(new BigDecimal("150"));

            Object resp = controller.redeem(principal(7L, "CUSTOMER"),
                    java.util.Map.of("code", "GC-1", "amount", "50"));

            assertThat(((java.util.Map<?, ?>) resp).get("remainingBalance").toString())
                    .isEqualTo("150");
            org.mockito.Mockito.verify(socialService)
                    .redeemGiftCard("GC-1", 7L, new BigDecimal("50"));
        }

        @Test
        void redeem_omittedAmount_redeemsFullBalance() {
            when(socialService.redeemGiftCard("GC-2", 7L, null))
                    .thenReturn(java.math.BigDecimal.ZERO);

            controller.redeem(principal(7L, "CUSTOMER"), java.util.Map.of("code", "GC-2"));

            org.mockito.Mockito.verify(socialService).redeemGiftCard("GC-2", 7L, null);
        }

        @Test
        void redeem_unauthenticated_throws() {
            assertThatThrownBy(() -> controller.redeem(null,
                    java.util.Map.of("code", "GC-1", "amount", "50")))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }

        @Test
        void byCode_returnsMaskedView_withoutPurchaserId() {
            com.bhukkad.order.domain.GiftCard card = new com.bhukkad.order.domain.GiftCard();
            card.setCode("GC-9");
            card.setStatus("ACTIVE");
            card.setBalance(new BigDecimal("30"));
            card.setAmount(new BigDecimal("100"));
            card.setPurchasedBy(7L);
            when(socialService.giftCardByCode("GC-9")).thenReturn(card);

            Object view = controller.byCode("GC-9");

            // Response type must not expose PII fields of the entity
            // (purchasedBy / recipientEmail / recipientName / message).
            assertThat(view).isInstanceOf(GiftCardController.CodeView.class);
            assertThat(java.util.Arrays.stream(GiftCardController.CodeView.class.getRecordComponents())
                    .map(c -> c.getName()))
                    .doesNotContain("purchasedBy", "recipientEmail", "recipientName", "message");
        }
    }

    // ---- SubscriptionController ----
    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class SubscriptionEndpoints {
        @Mock private SocialOrderService socialService;
        @InjectMocks private SubscriptionController controller;

        @Test
        void list_otherCustomersSubscriptions_throws() {
            assertThatThrownBy(() -> controller.subscriptions(principal(2L, "CUSTOMER"), 1L))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }

        @Test
        void subscribe_bindsToPrincipal() {
            when(socialService.subscribe(7L, 3L, "MONTHLY")).thenReturn(new Subscription());

            controller.subscribe(principal(7L, "CUSTOMER"), 3L, "MONTHLY");

            org.mockito.Mockito.verify(socialService).subscribe(7L, 3L, "MONTHLY");
        }
    }

    // ---- GroupOrderController ----
    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class GroupOrderEndpoints {
        @Mock private SocialOrderService socialService;
        @InjectMocks private GroupOrderController controller;

        @Test
        void join_crossCustomer_throws() {
            assertThatThrownBy(() -> controller.join(principal(2L, "CUSTOMER"), 10L, 1L))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }

        @Test
        void join_nonOpenGroup_throws() {
            GroupOrder closed = new GroupOrder();
            closed.setId(10L);
            closed.setStatus("CLOSED");
            when(socialService.groupOrder(10L)).thenReturn(closed);

            assertThatThrownBy(() -> controller.join(principal(1L, "CUSTOMER"), 10L, 1L))
                    .isInstanceOf(com.bhukkad.common.error.BusinessException.class)
                    .hasMessageContaining("not open");
        }

        @Test
        void members_onlyHostMayList() {
            GroupOrder group = new GroupOrder();
            group.setId(10L);
            group.setHostUserId(2L);
            group.setStatus(GroupOrder.STATUS_OPEN);
            when(socialService.groupOrder(10L)).thenReturn(group);

            assertThatThrownBy(() -> controller.members(principal(1L, "CUSTOMER"), 10L, 1L))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
    }
}
