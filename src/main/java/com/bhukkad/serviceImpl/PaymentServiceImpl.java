package com.bhukkad.serviceImpl;

import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.PaymentRepository;
import com.bhukkad.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    @Override
    @Transactional
    public Payment createPayment(Long orderId, String paymentMethod) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(Payment.PaymentMethod.valueOf(paymentMethod));
        payment.setAmount(order.getTotalAmount());
        payment.setStatus(Payment.PaymentStatus.PENDING);

        return paymentRepository.save(payment);
    }

    @Override
    @Transactional
    public Payment processPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        if (payment.getStatus() == Payment.PaymentStatus.COMPLETED) {
            throw new BusinessException("Payment already processed");
        }

        // In real implementation, integrate with payment gateway
        // For now, simulating successful payment
        payment.setTransactionId("TXN-" + UUID.randomUUID().toString());
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        payment.setCompletedAt(LocalDateTime.now());

        return paymentRepository.save(payment);
    }

    @Override
    public Payment getPaymentByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElse(null);
    }

    @Override
    @Transactional
    public void refundPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        if (payment.getStatus() != Payment.PaymentStatus.COMPLETED) {
            throw new BusinessException("Cannot refund incomplete payment");
        }

        // In real implementation, integrate with payment gateway for refund
        payment.setStatus(Payment.PaymentStatus.REFUNDED);
        paymentRepository.save(payment);
    }
}