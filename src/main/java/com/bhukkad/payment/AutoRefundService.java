package com.bhukkad.payment;

import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.PaymentRepository;
import com.bhukkad.serviceImpl.PaymentServiceImpl;
import com.bhukkad.util.PriceCalculator;
import com.bhukkad.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cancellation-policy-driven automatic refund orchestration.
 *
 * <p>Resolves the refund policy for a cancelled order and executes it against the
 * customer wallet (direct credit) or the payment gateway (full refund) depending
 * on the policy target. All execution is defensive: failures are logged and
 * swallowed, never rethrown.
 *
 * <p>Double-refund protection is two-layered. The {@code REFUNDED} payment status
 * is the database truth (and {@link PaymentServiceImpl#refundPayment} is itself
 * idempotent), while an in-memory check-and-store guard keeps duplicate calls in
 * this JVM from reaching the money path. Note: the Redis-backed
 * {@code IdempotencyService} has no {@code checkAndStore} primitive, so the
 * atomic claim below mirrors that contract locally.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoRefundService {

    private final RefundPolicyService refundPolicyService;
    private final PaymentServiceImpl paymentServiceImpl;
    private final WalletService walletService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    /** In-memory idempotency markers keyed by "refund:{orderId}:{reason}". */
    private final ConcurrentHashMap<String, Boolean> processedRefunds = new ConcurrentHashMap<>();

    /**
     * Loads the order and executes the automatic refund for a cancelled order.
     *
     * <p>Convenience overload for callers that only have an order id; the order
     * is fetched through the repository so controllers never need to depend on
     * repositories directly.</p>
     *
     * @param orderId            id of the cancelled order
     * @param cancellationReason the cancellation reason used for policy resolution
     * @return {@code true} when a refund was executed, {@code false} otherwise
     * @throws ResourceNotFoundException if no order exists with the given id
     */
    public boolean autoRefund(Long orderId, String cancellationReason) {
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return autoRefund(order, cancellationReason);
    }

    /**
     * Executes the automatic refund for a cancelled order if a policy applies.
     *
     * @param order              the cancelled order
     * @param cancellationReason the cancellation reason used for policy resolution
     * @return {@code true} when a refund was executed by this call, {@code false}
     *         when no policy applies, the refund was already processed, or the
     *         attempt failed (logged, never thrown)
     */
    public boolean autoRefund(Order order, String cancellationReason) {
        if (order == null || order.getId() == null) {
            log.warn("Auto refund skipped | order or order id is null");
            return false;
        }
        String idempotencyKey = "refund:" + order.getId() + ":" + cancellationReason;
        try {
            Optional<RefundPolicyService.RefundPolicy> policy =
                    refundPolicyService.computeRefund(order, cancellationReason);
            if (policy.isEmpty()) {
                log.info("No refund policy applies | orderId={} | reason={}", order.getId(), cancellationReason);
                return false;
            }
            if (!checkAndStore(idempotencyKey)) {
                log.info("Auto refund already processed | key={}", idempotencyKey);
                return false;
            }

            Payment payment = paymentRepository.findByOrderId(order.getId()).orElse(null);
            if (payment != null && payment.getStatus() == Payment.PaymentStatus.REFUNDED) {
                log.info("Payment already refunded | orderId={} | paymentId={}", order.getId(), payment.getId());
                return false;
            }

            double amount = PriceCalculator.roundToTwoDecimals(
                    order.getTotalAmount() * policy.get().percent() / 100.0);
            String target = policy.get().target();

            if (RefundPolicyService.TARGET_WALLET.equalsIgnoreCase(target)) {
                walletService.credit(order.getCustomer(), amount,
                        WalletTransaction.TransactionType.ORDER_REFUND, payment,
                        "Auto refund for cancelled order " + order.getOrderNumber());
                log.info("Auto refund to wallet completed | orderId={} | amount={}", order.getId(), amount);
                return true;
            }

            if (RefundPolicyService.TARGET_GATEWAY.equalsIgnoreCase(target)) {
                paymentServiceImpl.processRefund(order.getId(), amount, cancellationReason);
                log.info("Auto refund via gateway completed | orderId={} | amount={} | reason={}",
                        order.getId(), amount, cancellationReason);
                return true;
            }

            processedRefunds.remove(idempotencyKey);
            log.warn("Unknown refund target | orderId={} | target={}", order.getId(), target);
            return false;
        } catch (Exception ex) {
            processedRefunds.remove(idempotencyKey);
            log.error("Auto refund failed | orderId={} | reason={}", order.getId(), cancellationReason, ex);
            return false;
        }
    }

    /**
     * Atomically claims an idempotency key. Returns {@code false} when the key was
     * already claimed, preventing duplicate refunds for the same order and reason.
     */
    private boolean checkAndStore(String key) {
        return processedRefunds.putIfAbsent(key, Boolean.TRUE) == null;
    }
}
