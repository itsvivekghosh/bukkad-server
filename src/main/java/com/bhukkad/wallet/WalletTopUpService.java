package com.bhukkad.wallet;

import com.bhukkad.dto.response.PaymentResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.payment.PaymentGateway;
import com.bhukkad.payment.PaymentProperties;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.PaymentRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletTopUpService {

    private final CustomerRepository customerRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentProperties paymentProperties;
    private final WalletService walletService;
    private final SecurityUtils securityUtils;

    @Transactional
    public PaymentResponse initiateTopUp(Double amount, String idempotencyKey) {
        if (amount == null || amount <= 0) {
            throw new BusinessException("Amount must be positive");
        }
        if (!paymentProperties.getRazorpay().isEnabled()) {
            throw new BusinessException("Online wallet top-up requires Razorpay to be enabled");
        }

        if (StringUtils.hasText(idempotencyKey)) {
            var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return toResponse(existing.get());
            }
        }

        Long customerId = securityUtils.getCurrentUserId();
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new BusinessException("Customer not found"));

        double roundedAmount = PriceCalculator.roundToTwoDecimals(amount);
        String receipt = "WALLET-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        PaymentGateway.GatewayOrderResult gatewayOrder = paymentGateway.createOrder(
                PaymentGateway.GatewayOrderRequest.builder()
                        .amount(roundedAmount)
                        .currency(paymentProperties.getRazorpay().getCurrency())
                        .receipt(receipt)
                        .idempotencyKey(idempotencyKey)
                        .build());

        Payment payment = new Payment();
        payment.setCustomer(customer);
        payment.setPurpose(Payment.PaymentPurpose.WALLET_TOP_UP);
        payment.setPaymentMethod(Payment.PaymentMethod.UPI);
        payment.setAmount(roundedAmount);
        payment.setGatewayAmount(roundedAmount);
        payment.setWalletAmount(0.0);
        payment.setStatus(Payment.PaymentStatus.PENDING);
        payment.setGatewayOrderId(gatewayOrder.gatewayOrderId());
        payment.setPaymentGatewayResponse(gatewayOrder.rawResponse());
        payment.setIdempotencyKey(idempotencyKey);
        payment = paymentRepository.save(payment);

        return toResponse(payment);
    }

    @Transactional
    public void completeTopUp(Payment payment, String gatewayPaymentId) {
        if (payment.getPurpose() != Payment.PaymentPurpose.WALLET_TOP_UP) {
            return;
        }
        if (payment.getStatus() == Payment.PaymentStatus.COMPLETED) {
            return;
        }
        payment.setGatewayPaymentId(gatewayPaymentId);
        payment.setTransactionId(gatewayPaymentId);
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        payment.setCompletedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        walletService.credit(
                payment.getCustomer(),
                payment.getAmount(),
                WalletTransaction.TransactionType.TOP_UP,
                payment,
                "Wallet top-up via payment gateway");
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
