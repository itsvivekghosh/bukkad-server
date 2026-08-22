package com.bhukkad.serviceImpl;

import com.bhukkad.dto.response.PaymentResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.idempotency.PaymentIdempotencyService;
import com.bhukkad.payment.PaymentGateway;
import com.bhukkad.payment.PaymentProperties;
import com.bhukkad.payment.strategy.PaymentContext;
import com.bhukkad.payment.strategy.PaymentStrategyFactory;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.PaymentRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.NotificationService;
import com.bhukkad.service.PaymentService;
import com.bhukkad.timeline.OrderTimelineService;
import com.bhukkad.util.PriceCalculator;
import com.bhukkad.wallet.WalletService;
import com.bhukkad.wallet.WalletTopUpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final Set<Payment.PaymentMethod> GATEWAY_METHODS = Set.of(
            Payment.PaymentMethod.CREDIT_CARD,
            Payment.PaymentMethod.DEBIT_CARD,
            Payment.PaymentMethod.UPI,
            Payment.PaymentMethod.NET_BANKING
    );

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentProperties paymentProperties;
    private final PaymentStrategyFactory paymentStrategyFactory;
    private final PaymentIdempotencyService paymentIdempotencyService;
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;
    private final WalletService walletService;
    private final WalletTopUpService walletTopUpService;
    private final OrderTimelineService orderTimelineService;

    @Override
    @Transactional
    public Payment createPayment(Long orderId, String paymentMethod, String idempotencyKey) {
        if (StringUtils.hasText(idempotencyKey)) {
            var cached = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        double walletAmount = order.getWalletAmountUsed() != null ? order.getWalletAmountUsed() : 0.0;
        double gatewayAmount = order.getTotalAmount();
        double totalBill = PriceCalculator.roundToTwoDecimals(walletAmount + gatewayAmount);

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setCustomer(order.getCustomer());
        payment.setPurpose(Payment.PaymentPurpose.ORDER);
        payment.setPaymentMethod(Payment.PaymentMethod.valueOf(paymentMethod));
        payment.setAmount(totalBill);
        payment.setWalletAmount(walletAmount);
        payment.setGatewayAmount(gatewayAmount);
        payment.setStatus(Payment.PaymentStatus.PENDING);
        payment.setIdempotencyKey(idempotencyKey);

        if (requiresGateway(payment.getPaymentMethod()) && gatewayAmount > 0) {
            PaymentGateway.GatewayOrderResult gatewayOrder = paymentGateway.createOrder(
                    PaymentGateway.GatewayOrderRequest.builder()
                            .amount(gatewayAmount)
                            .currency(paymentProperties.getRazorpay().getCurrency())
                            .receipt(order.getOrderNumber())
                            .idempotencyKey(idempotencyKey)
                            .build());
            payment.setGatewayOrderId(gatewayOrder.gatewayOrderId());
            payment.setPaymentGatewayResponse(gatewayOrder.rawResponse());
        }

        return paymentRepository.save(payment);
    }

    @Override
    @Transactional
    public Payment processPayment(Long paymentId, String idempotencyKey) {
        if (StringUtils.hasText(idempotencyKey)) {
            var cached = paymentIdempotencyService.findCompletedPayment(idempotencyKey);
            if (cached.isPresent()) {
                return cached.get();
            }
            // DB-backed guard: prevents concurrent duplicate processing even if
            // the Redis cache was lost. Throws for IN_PROGRESS duplicates.
            paymentIdempotencyService.beginPaymentProcess(idempotencyKey);
        }

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        if (payment.getStatus() == Payment.PaymentStatus.COMPLETED) {
            return payment;
        }

        try {
            return doProcessPayment(payment, idempotencyKey);
        } catch (RuntimeException ex) {
            paymentIdempotencyService.failPaymentProcess(idempotencyKey);
            throw ex;
        }
    }

    private Payment doProcessPayment(Payment payment, String idempotencyKey) {
        if (payment.getPaymentMethod() == Payment.PaymentMethod.CASH_ON_DELIVERY) {
            return paymentRepository.save(payment);
        }

        if (payment.getPaymentMethod() == Payment.PaymentMethod.WALLET
                || payment.getGatewayAmount() == null
                || payment.getGatewayAmount() <= 0) {
            payment.setTransactionId("WALLET-" + payment.getOrder().getOrderNumber());
            payment.setStatus(Payment.PaymentStatus.COMPLETED);
            payment.setCompletedAt(LocalDateTime.now());
            payment = paymentRepository.save(payment);
            paymentIdempotencyService.completePaymentProcess(idempotencyKey, payment);
            return payment;
        }

        PaymentContext context = new PaymentContext(
                payment.getOrder(), payment, idempotencyKey,
                payment.getGatewayAmount() != null ? payment.getGatewayAmount() : 0.0
        );

        var strategy = paymentStrategyFactory.getStrategy(payment.getPaymentMethod());
        payment = strategy.process(context);
        payment = paymentRepository.save(payment);

        if (payment.getStatus() == Payment.PaymentStatus.FAILED) {
            throw new BusinessException("Payment failed");
        }

        paymentIdempotencyService.completePaymentProcess(idempotencyKey, payment);
        return payment;
    }

    @Override
    public Payment getPaymentByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentForOrder(Long orderId) {
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getCustomer().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("You can only access your own payments");
        }

        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for order"));
        return toResponse(payment);
    }

    @Override
    @Transactional
    public void refundPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        if (payment.getStatus() == Payment.PaymentStatus.REFUNDED) {
            return;
        }
        if (payment.getStatus() != Payment.PaymentStatus.COMPLETED
                && payment.getStatus() != Payment.PaymentStatus.PENDING) {
            throw new BusinessException("Cannot refund payment in status: " + payment.getStatus());
        }

        Order order = payment.getOrder();
        double walletRefund = payment.getWalletAmount() != null ? payment.getWalletAmount() : 0.0;
        double gatewayRefund = payment.getGatewayAmount() != null ? payment.getGatewayAmount() : 0.0;

        if (walletRefund > 0 && order != null) {
            walletService.credit(
                    order.getCustomer(),
                    walletRefund,
                    WalletTransaction.TransactionType.ORDER_REFUND,
                    payment,
                    "Refund for order " + order.getOrderNumber());
        } else if (payment.getPaymentMethod() == Payment.PaymentMethod.WALLET && order != null) {
            walletService.credit(
                    order.getCustomer(),
                    payment.getAmount(),
                    WalletTransaction.TransactionType.ORDER_REFUND,
                    payment,
                    "Refund for order " + order.getOrderNumber());
        }

        if (gatewayRefund > 0 && requiresGateway(payment.getPaymentMethod())
                && StringUtils.hasText(payment.getGatewayPaymentId())) {
            PaymentGateway.GatewayRefundResult refund = paymentGateway.refundPayment(
                    PaymentGateway.GatewayRefundRequest.builder()
                            .gatewayPaymentId(payment.getGatewayPaymentId())
                            .amount(gatewayRefund)
                            .idempotencyKey("refund-" + paymentId)
                            .build());
            if (!refund.success()) {
                throw new BusinessException("Gateway refund failed");
            }
            payment.setPaymentGatewayResponse(refund.rawResponse());
        }

        payment.setStatus(Payment.PaymentStatus.REFUNDED);
        paymentRepository.save(payment);
        if (order != null) {
            recordRefundTimeline(order, payment);
            notificationService.sendPaymentRefunded(order.getId(), payment.getAmount());
        }
    }

    /**
     * Records an ORDER_REFUNDED audit-trail event for event sourcing. Best
     * effort — a failure here must never fail the refund itself.
     */
    private void recordRefundTimeline(Order order, Payment payment) {
        try {
            orderTimelineService.recordEvent(
                    order.getId(),
                    "ORDER_REFUNDED",
                    order.getStatus() != null ? order.getStatus().name() : null,
                    "Refund of " + payment.getAmount() + " completed",
                    securityUtils.getCurrentUserId(),
                    "SYSTEM");
        } catch (Exception ex) {
            log.warn("Failed to record refund timeline event | orderId={}", order.getId(), ex);
        }
    }

    @Override
    @Transactional
    public void completeWebhookPayment(String gatewayOrderId, String gatewayPaymentId) {
        Payment payment = paymentRepository.findByGatewayOrderId(gatewayOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for gateway order"));

        if (payment.getStatus() == Payment.PaymentStatus.COMPLETED) {
            return;
        }

        if (payment.getPurpose() == Payment.PaymentPurpose.WALLET_TOP_UP) {
            walletTopUpService.completeTopUp(payment, gatewayPaymentId);
            return;
        }

        payment.setGatewayPaymentId(gatewayPaymentId);
        payment.setTransactionId(gatewayPaymentId);
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        payment.setCompletedAt(LocalDateTime.now());
        paymentRepository.save(payment);
    }

    private boolean requiresGateway(Payment.PaymentMethod method) {
        return GATEWAY_METHODS.contains(method) && paymentProperties.getRazorpay().isEnabled();
    }

    private PaymentResponse toResponse(Payment payment) {
        return PaymentResponse.builder()
                .id(payment.getId())
                .orderId(payment.getOrder() != null ? payment.getOrder().getId() : null)
                .paymentMethod(payment.getPaymentMethod().name())
                .status(payment.getStatus().name())
                .amount(payment.getAmount())
                .gatewayOrderId(payment.getGatewayOrderId())
                .gatewayPaymentId(payment.getGatewayPaymentId())
                .transactionId(payment.getTransactionId())
                .purpose(payment.getPurpose().name())
                .build();
    }
}
