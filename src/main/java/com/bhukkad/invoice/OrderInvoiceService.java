package com.bhukkad.invoice;

import com.bhukkad.config.NotificationProperties;
import com.bhukkad.dto.response.OrderInvoiceResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderInvoice;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.notification.ResilientEmailSender;
import com.bhukkad.repository.OrderInvoiceRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.Year;

/**
 * Generates, stores, emails and retrieves GST order invoices.
 *
 * <p>The lifecycle of an invoice is:
 * <ol>
 *   <li>The order reaches {@code DELIVERED} and {@link #generateOnDelivery(Order)}
 *       persists the immutable money snapshot (subtotal, delivery fee, CGST, SGST,
 *       discount, total) together with a deterministic invoice number.</li>
 *   <li>A PDF is rendered from that snapshot by {@link InvoicePdfRenderer} and
 *       uploaded by {@link InvoicePdfStorageService}.</li>
 *   <li>The PDF is emailed to the customer as an attachment, as required for a
 *       GST tax invoice.</li>
 * </ol>
 *
 * <p><strong>Failure policy.</strong> Only step 1 is essential. PDF rendering,
 * upload and email are all best-effort side effects: none of them may fail the
 * transaction that marks an order delivered. Storage returns {@code null} keys on
 * failure and the email sender reports a boolean, so a degraded provider simply
 * leaves {@code pdfStorageKey} or {@code emailedAt} unset. The PDF is always
 * re-renderable on demand from the persisted amounts, which is what
 * {@link #renderPdf(Long)} does for the download endpoint.
 *
 * <p><strong>Lazy loading.</strong> Every {@link Order} association is
 * {@code FetchType.LAZY} and {@code OrderInvoice.order} is lazy as well, while the
 * renderer needs the customer, restaurant, delivery address and order items. Any
 * path that renders a PDF therefore warms the persistence context with
 * {@link OrderRepository#findByIdWithDetails(Long)} first, which JOIN FETCHes that
 * exact graph, instead of letting the renderer trigger a query per association.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderInvoiceService {

    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final OrderInvoiceRepository orderInvoiceRepository;
    private final OrderRepository orderRepository;
    private final InvoicePdfRenderer invoicePdfRenderer;
    private final InvoicePdfStorageService invoicePdfStorageService;
    private final ResilientEmailSender resilientEmailSender;
    private final NotificationProperties notificationProperties;

    /**
     * Generates a GST invoice when an order is delivered. Idempotent per order:
     * an order that already has an invoice is returned unchanged, so a retried
     * delivery callback cannot produce a second tax document or a second email.
     *
     * @param order delivered order
     * @return generated or existing invoice
     */
    @Transactional
    public OrderInvoiceResponse generateOnDelivery(Order order) {
        if (order.getStatus() != Order.OrderStatus.DELIVERED) {
            throw new BusinessException("Invoice can only be generated for delivered orders");
        }

        return orderInvoiceRepository.findByOrderId(order.getId())
                .map(this::toResponse)
                .orElseGet(() -> {
                    OrderInvoice invoice = createInvoice(order);
                    publishPdf(invoice);
                    return toResponse(invoice);
                });
    }

    /**
     * Retrieves the invoice for a given order.
     *
     * @param orderId order identifier
     * @return invoice details
     */
    public OrderInvoiceResponse getByOrderId(Long orderId) {
        OrderInvoice invoice = orderInvoiceRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found for order"));
        return toResponse(invoice);
    }

    /**
     * Produces the invoice PDF bytes for download.
     *
     * <p>Always rendered from the persisted invoice rather than served from
     * storage, so the endpoint works identically whether or not object storage is
     * enabled and can never return a stale document.
     *
     * @param orderId order identifier
     * @return rendered PDF bytes
     * @throws ResourceNotFoundException when the order has no invoice yet
     */
    public byte[] renderPdf(Long orderId) {
        OrderInvoice invoice = orderInvoiceRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found for order"));
        orderRepository.findByIdWithDetails(orderId);
        return invoicePdfRenderer.render(invoice);
    }

    /**
     * Returns the file name clients should save a downloaded invoice under.
     *
     * @param orderId order identifier
     * @return {@code INV-<year>-<order>.pdf}
     */
    public String pdfFileName(Long orderId) {
        return generateInvoiceNumber(orderId) + ".pdf";
    }

    /**
     * Renders the invoice PDF, uploads it, and emails it to the customer.
     *
     * <p>Every step is guarded: a failure is logged and recorded on the invoice
     * row (attempt counters, unset timestamps) but never propagated, because this
     * runs inside the transaction that marks an order delivered.
     */
    private void publishPdf(OrderInvoice invoice) {
        byte[] pdf;
        try {
            // The order graph is already in the persistence context: the caller
            // loaded the order it passed to generateOnDelivery.
            pdf = invoicePdfRenderer.render(invoice);
        } catch (Exception ex) {
            log.error("INVOICE_PDF_GENERATION_FAILED | invoice={} | error={}",
                    invoice.getInvoiceNumber(), ex.getMessage(), ex);
            return;
        }

        String storageKey = invoicePdfStorageService.store(invoice.getInvoiceNumber(), pdf);
        invoice.setPdfStorageKey(storageKey);
        invoice.setPdfGeneratedAt(LocalDateTime.now());

        emailInvoice(invoice, pdf);
        orderInvoiceRepository.save(invoice);
    }

    /**
     * Emails the invoice PDF to the ordering customer.
     *
     * <p>Follows the platform email convention: nothing is sent unless both the
     * global notification flag and the email channel flag are on. When disabled
     * the intent is logged so local runs stay observable, and no attempt is
     * counted because none was made.
     *
     * <p>{@code emailedAt} and {@code emailRecipient} are set only when the
     * provider actually accepted the message, so the pending-email index stays a
     * reliable retry queue.
     */
    private void emailInvoice(OrderInvoice invoice, byte[] pdf) {
        String recipient = resolveRecipient(invoice);
        if (!StringUtils.hasText(recipient)) {
            log.warn("INVOICE_EMAIL_SKIPPED | invoice={} | reason=no recipient email", invoice.getInvoiceNumber());
            return;
        }

        String subject = "Your Bhukkad GST invoice " + invoice.getInvoiceNumber();
        String body = buildEmailBody(invoice);

        if (!notificationProperties.isEnabled() || !notificationProperties.getEmail().isEnabled()) {
            log.info("INVOICE_EMAIL | to={} | subject={} | attachmentBytes={}", recipient, subject, pdf.length);
            return;
        }

        invoice.setEmailAttempts(nz(invoice.getEmailAttempts()) + 1);
        try {
            boolean sent = resilientEmailSender.sendWithAttachment(
                    notificationProperties.getEmail().getFrom(),
                    recipient,
                    subject,
                    body,
                    invoice.getInvoiceNumber() + ".pdf",
                    pdf,
                    PDF_CONTENT_TYPE);
            if (sent) {
                invoice.setEmailedAt(LocalDateTime.now());
                invoice.setEmailRecipient(recipient);
                log.info("INVOICE_EMAIL_SENT | invoice={} | to={}", invoice.getInvoiceNumber(), recipient);
            } else {
                log.warn("INVOICE_EMAIL_NOT_SENT | invoice={} | to={} | attempts={}",
                        invoice.getInvoiceNumber(), recipient, invoice.getEmailAttempts());
            }
        } catch (Exception ex) {
            log.error("INVOICE_EMAIL_FAILED | invoice={} | to={} | error={}",
                    invoice.getInvoiceNumber(), recipient, ex.getMessage(), ex);
        }
    }

    private String resolveRecipient(OrderInvoice invoice) {
        Order order = invoice.getOrder();
        if (order == null || order.getCustomer() == null) {
            return null;
        }
        return order.getCustomer().getEmail();
    }

    private String buildEmailBody(OrderInvoice invoice) {
        Order order = invoice.getOrder();
        String orderNumber = order != null ? order.getOrderNumber() : "-";
        return "Hi,\n\n"
                + "Thanks for ordering with Bhukkad. Your GST tax invoice for order "
                + orderNumber + " is attached.\n\n"
                + "Invoice number: " + invoice.getInvoiceNumber() + "\n"
                + "Total paid: INR " + invoice.getTotalAmount() + "\n\n"
                + "This is a computer-generated invoice.\n\n"
                + "- Team Bhukkad";
    }

    private OrderInvoice createInvoice(Order order) {
        double subtotal = order.getSubtotal() != null ? order.getSubtotal() : 0.0;
        double deliveryFee = order.getDeliveryFee() != null ? order.getDeliveryFee() : 0.0;
        double discount = order.getDiscountAmount() != null ? order.getDiscountAmount() : 0.0;
        double taxAmount = order.getTaxAmount() != null
                ? order.getTaxAmount()
                : PriceCalculator.calculateTax(subtotal);
        double cgst = PriceCalculator.roundToTwoDecimals(taxAmount / 2.0);
        double sgst = PriceCalculator.roundToTwoDecimals(taxAmount / 2.0);
        double total = order.getTotalAmount() != null
                ? order.getTotalAmount()
                : PriceCalculator.calculateTotal(subtotal, deliveryFee, taxAmount, discount);

        OrderInvoice invoice = new OrderInvoice();
        invoice.setOrder(order);
        invoice.setInvoiceNumber(generateInvoiceNumber(order.getId()));
        invoice.setSubtotal(subtotal);
        invoice.setDeliveryFee(deliveryFee);
        invoice.setTaxAmount(taxAmount);
        invoice.setCgstAmount(cgst);
        invoice.setSgstAmount(sgst);
        invoice.setDiscountAmount(discount);
        invoice.setTotalAmount(total);
        invoice.setRestaurantGstin(order.getRestaurant().getLicenseNumber());
        invoice.setIssuedAt(LocalDateTime.now());
        invoice.setEmailAttempts(0);

        return orderInvoiceRepository.save(invoice);
    }

    private String generateInvoiceNumber(Long orderId) {
        int year = Year.now().getValue();
        return String.format("INV-%d-%08d", year, orderId);
    }

    private OrderInvoiceResponse toResponse(OrderInvoice invoice) {
        Order order = invoice.getOrder();
        boolean stored = StringUtils.hasText(invoice.getPdfStorageKey());
        return OrderInvoiceResponse.builder()
                .id(invoice.getId())
                .orderId(order != null ? order.getId() : null)
                .orderNumber(order != null ? order.getOrderNumber() : null)
                .invoiceNumber(invoice.getInvoiceNumber())
                .subtotal(invoice.getSubtotal())
                .deliveryFee(invoice.getDeliveryFee())
                .taxAmount(invoice.getTaxAmount())
                .cgstAmount(invoice.getCgstAmount())
                .sgstAmount(invoice.getSgstAmount())
                .discountAmount(invoice.getDiscountAmount())
                .totalAmount(invoice.getTotalAmount())
                .restaurantGstin(invoice.getRestaurantGstin())
                .issuedAt(invoice.getIssuedAt() != null ? invoice.getIssuedAt().toString() : null)
                .pdfUrl(stored ? invoicePdfStorageService.presignedUrl(invoice.getPdfStorageKey()) : null)
                .pdfAvailable(stored)
                .emailedAt(invoice.getEmailedAt() != null ? invoice.getEmailedAt().toString() : null)
                .build();
    }

    private int nz(Integer value) {
        return value != null ? value : 0;
    }
}
