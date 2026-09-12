package com.bhukkad.payment.domain.event;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Publishes payment domain events via the transactional outbox (plan §6.2,
 * {@code payment.events.v1}): {@code PaymentSettled}, {@code WalletCredited}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventPublisher {

    public static final String TOPIC = "payment.events.v1";
    public static final String TYPE_PAYMENT_SETTLED = "PaymentSettled";
    public static final String TYPE_WALLET_CREDITED = "WalletCredited";
    public static final String TYPE_SETTLEMENT_RUN_COMPLETED = "PaymentSettlementRunCompleted";

    private final OutboxClient outboxClient;

    public void paymentSettled(Long paymentId, Long orderId, Long customerId, BigDecimal amount) {
        enqueue(TYPE_PAYMENT_SETTLED, paymentId,
                "{\"paymentId\":%d,\"orderId\":%d,\"customerId\":%d,\"amount\":%s}"
                        .formatted(paymentId, orderId, customerId, amount));
    }

    /**
     * Emitted by the automated settlement batch (W2 / G1). Consumers: revenue
     * dashboards and the payout pipeline; registered in {@code docs/event-catalog.md}.
     */
    public void settlementRunCompleted(Long runId, java.time.LocalDate runDate,
                                       int restaurantsSettled, int rowsSettled, BigDecimal totalNet) {
        enqueue(TYPE_SETTLEMENT_RUN_COMPLETED, runId,
                "{\"runId\":%d,\"runDate\":\"%s\",\"restaurantsSettled\":%d,\"rowsSettled\":%d,\"totalNet\":%s}"
                        .formatted(runId, runDate, restaurantsSettled, rowsSettled, totalNet));
    }

    public void walletCredited(Long customerId, BigDecimal amount, Long transactionId) {
        enqueue(TYPE_WALLET_CREDITED, customerId,
                "{\"customerId\":%d,\"amount\":%s,\"transactionId\":%d}"
                        .formatted(customerId, amount, transactionId));
    }

    private void enqueue(String type, Long aggregateId, String payload) {
        try {
            PlatformEventMessage message = PlatformEventMessage.of(type, String.valueOf(aggregateId), payload);
            outboxClient.enqueue(message, aggregateId);
            log.info("PAYMENT_EVENT_ENQUEUED | type={} | aggregateId={}", type, aggregateId);
        } catch (Exception e) {
            log.error("PAYMENT_EVENT_ENQUEUE_FAILED | type={} | aggregateId={}", type, aggregateId, e);
        }
    }
}