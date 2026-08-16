package com.bhukkad.repository;

import com.bhukkad.entity.OrderInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Access to the GST invoice issued for an order.
 *
 * <p>The relationship is one invoice per order, enforced by the unique constraint on
 * {@code order_invoices.order_id} in {@code V17__trust_and_compliance.sql}. Lookups are therefore
 * keyed on the order rather than on the invoice id: callers arrive holding an order (from a
 * download request or from the delivery hook) and never from an invoice number.</p>
 *
 * <p>Invoices are effectively immutable once issued — a tax document must not silently change after
 * the customer has received it. {@code OrderInvoiceService} writes a row on the first delivery of
 * an order and afterwards only updates storage bookkeeping (the PDF object key), never the tax
 * figures. There is deliberately no delete method: cancelling out an issued invoice is a credit
 * note, not a row removal.</p>
 *
 * @see com.bhukkad.entity.OrderInvoice
 * @see com.bhukkad.invoice.OrderInvoiceService
 */
@Repository
public interface OrderInvoiceRepository extends JpaRepository<OrderInvoice, Long> {

    /**
     * Finds the invoice issued for an order.
     *
     * <p>Returns at most one row because {@code order_id} is unique. This is the idempotency check
     * on the delivery path — {@code generateOnDelivery} returns the existing invoice instead of
     * issuing a second one when delivery is marked more than once — and it is also the lookup
     * behind both the JSON invoice endpoint and the PDF download.</p>
     *
     * @param orderId order whose invoice is wanted
     * @return the invoice, or empty when none has been issued yet
     */
    Optional<OrderInvoice> findByOrderId(Long orderId);

    /**
     * Whether an order already has an invoice, without loading it.
     *
     * <p>Preferred over {@link #findByOrderId(Long)} where only the presence matters (reporting and
     * reconciliation checks over many orders), since it avoids hydrating an entity that would be
     * discarded.</p>
     *
     * @param orderId order to check
     * @return {@code true} when an invoice exists for the order
     */
    boolean existsByOrderId(Long orderId);
}
