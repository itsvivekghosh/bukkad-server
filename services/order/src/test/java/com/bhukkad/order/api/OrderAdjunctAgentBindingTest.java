package com.bhukkad.order.api;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderDeliveryProof;
import com.bhukkad.order.domain.OrderDeliveryProofRepository;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.service.OrderInvoiceService;
import com.bhukkad.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit LOW-IDOR-5: the delivery-proof photo and verify endpoints were
 * role-only — ANY rider could attach a photo or consume the handover OTP of
 * ANY order. Like the picked-up/delivered transitions, the caller must be the
 * order's assigned rider (admins override).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderAdjunctAgentBindingTest {

    private static final Long ORDER_ID = 11L;
    private static final Long ASSIGNED_AGENT = 9L;
    private static final TokenPrincipal RIDER =
            new TokenPrincipal(9L, "rider@bhukkad.dev", "DELIVERY_AGENT");
    private static final TokenPrincipal OTHER_RIDER =
            new TokenPrincipal(13L, "other@bhukkad.dev", "DELIVERY_AGENT");
    private static final TokenPrincipal ADMIN =
            new TokenPrincipal(1L, "admin@bhukkad.dev", "ADMIN");

    @Mock private OrderService orderService;
    @Mock private OrderInvoiceService invoiceService;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderDeliveryProofRepository proofRepository;
    @InjectMocks private OrderAdjunctController controller;

    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order();
        order.setId(ORDER_ID);
        order.setStatus("OUT_FOR_DELIVERY");
        order.setDeliveryAgentId(ASSIGNED_AGENT);
    }

    private OrderDeliveryProof issuedProof() {
        OrderDeliveryProof proof = new OrderDeliveryProof();
        proof.setOrderId(ORDER_ID);
        proof.setOtpHash(OrderAdjunctController.hashOtp("123456"));
        proof.setOtpIssuedAt(java.time.LocalDateTime.now().minusSeconds(10));
        return proof;
    }

    @Test
    void photoUploadUrl_assignedRider_succeeds() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        Map<String, Object> body = controller.photoUploadUrl(RIDER, ORDER_ID, Map.of("contentType", "image/jpeg"));

        assertThat(body).containsEntry("contentType", "image/jpeg");
    }

    @Test
    void photoUploadUrl_otherRider_denied() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> controller.photoUploadUrl(
                OTHER_RIDER, ORDER_ID, Map.of("contentType", "image/jpeg")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not assigned");
    }

    @Test
    void photoUploadUrl_admin_bypassesAssignment() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        Map<String, Object> body = controller.photoUploadUrl(ADMIN, ORDER_ID, Map.of("contentType", "image/jpeg"));

        assertThat(body).containsEntry("contentType", "image/jpeg");
    }

    @Test
    void verifyOtp_assignedRider_succeeds() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(issuedProof()));

        Map<String, Object> result = controller.verifyOtp(RIDER, ORDER_ID, "123456");

        assertThat(result).containsEntry("verified", true);
    }

    @Test
    void verifyOtp_otherRider_cannotConsumeHandoverOtp() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(issuedProof()));

        assertThatThrownBy(() -> controller.verifyOtp(OTHER_RIDER, ORDER_ID, "123456"))
                .isInstanceOf(AccessDeniedException.class);
        // The OTP must remain unconsumed.
        verify(proofRepository, never()).save(anyProof());
    }

    private static OrderDeliveryProof anyProof() {
        return org.mockito.ArgumentMatchers.any(OrderDeliveryProof.class);
    }
}
