package com.bhukkad.invoice;

import com.bhukkad.config.NotificationProperties;
import com.bhukkad.dto.response.OrderInvoiceResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderInvoice;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.notification.ResilientEmailSender;
import com.bhukkad.repository.OrderInvoiceRepository;
import com.bhukkad.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Year;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderInvoiceService}, the GST invoice lifecycle.
 *
 * <h2>Why the properties object is real and not a mock</h2>
 * <p>{@link NotificationProperties} is a plain holder, and the email path reads two independent
 * flags. Mocking it would mean stubbing getters that some tests never reach, which Mockito's strict
 * stub checking rejects. Note that {@code Email.enabled} defaults to <b>false</b> in production
 * configuration, so tests that want the send path must switch both flags on explicitly; that default
 * is itself asserted below.</p>
 *
 * <h2>What these tests are actually protecting</h2>
 * <ul>
 *   <li><b>The money snapshot is immutable and self-consistent.</b> A GST tax invoice may not change
 *       after issue, so the amounts are copied onto the invoice row instead of being recomputed from
 *       a mutable order. CGST and SGST must each be exactly half of the tax.</li>
 *   <li><b>Idempotency per order.</b> Delivery events can be replayed. The second call must return
 *       the existing invoice without re-rendering a PDF or emailing the customer a duplicate.</li>
 *   <li><b>PDF, upload and email are best-effort.</b> None of them may fail the transaction that
 *       marks an order delivered; a renderer or provider outage has to leave the invoice row intact
 *       with its optional columns unset.</li>
 *   <li><b>{@code emailedAt} and {@code emailRecipient} mean "delivered to the provider".</b> They
 *       are set only on a successful send, never at issue time, so they stay usable as the retry
 *       backlog signal. {@code emailAttempts} counts only real attempts.</li>
 *   <li><b>Downloads re-render from the snapshot</b> rather than serving a stale object, and warm the
 *       lazy order graph first because {@code open-in-view} is disabled.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderInvoiceServiceTest {

    private static final Long ORDER_ID = 42L;
    private static final String EMAIL = "diner@example.com";
    private static final byte[] PDF = new byte[]{'%', 'P', 'D', 'F'};

    @Mock
    private OrderInvoiceRepository orderInvoiceRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InvoicePdfRenderer invoicePdfRenderer;

    @Mock
    private InvoicePdfStorageService invoicePdfStorageService;

    @Mock
    private ResilientEmailSender resilientEmailSender;

    private NotificationProperties notificationProperties;
    private OrderInvoiceService service;

    /** Captured by the {@code save} stub so assertions can inspect the persisted state. */
    private OrderInvoice saved;

    @BeforeEach
    void setUp() {
        notificationProperties = new NotificationProperties();
        service = new OrderInvoiceService(
                orderInvoiceRepository,
                orderRepository,
                invoicePdfRenderer,
                invoicePdfStorageService,
                resilientEmailSender,
                notificationProperties);
    }

    private Order deliveredOrder() {
        Restaurant restaurant = new Restaurant();
        restaurant.setLicenseNumber("29ABCDE1234F1Z5");

        Customer customer = new Customer();
        customer.setEmail(EMAIL);

        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNumber("ORD-42");
        order.setStatus(Order.OrderStatus.DELIVERED);
        order.setSubtotal(200.0);
        order.setDeliveryFee(30.0);
        order.setTaxAmount(18.0);
        order.setDiscountAmount(8.0);
        order.setTotalAmount(240.0);
        order.setRestaurant(restaurant);
        order.setCustomer(customer);
        return order;
    }

    private void stubSave() {
        when(orderInvoiceRepository.save(any(OrderInvoice.class))).thenAnswer(invocation -> {
            saved = invocation.getArgument(0);
            return saved;
        });
    }

    private void enableEmail() {
        notificationProperties.setEnabled(true);
        notificationProperties.getEmail().setEnabled(true);
    }

    // ---------------------------------------------------------------- guards

    @Test
    void generateOnDelivery_orderNotDelivered_rejected() {
        Order order = deliveredOrder();
        order.setStatus(Order.OrderStatus.OUT_FOR_DELIVERY);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.generateOnDelivery(order));

        assertEquals("Invoice can only be generated for delivered orders", ex.getMessage());
        verifyNoInteractions(orderInvoiceRepository);
        verifyNoInteractions(invoicePdfRenderer);
    }

    // ---------------------------------------------------------------- money snapshot

    @Test
    void generateOnDelivery_snapshotsAmountsAndSplitsTaxEvenly() {
        when(orderInvoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(invoicePdfRenderer.render(any(OrderInvoice.class))).thenReturn(PDF);
        stubSave();

        OrderInvoiceResponse response = service.generateOnDelivery(deliveredOrder());

        assertEquals(200.0, saved.getSubtotal());
        assertEquals(30.0, saved.getDeliveryFee());
        assertEquals(18.0, saved.getTaxAmount());
        // A GST invoice must show CGST and SGST separately, each exactly half of the tax.
        assertEquals(9.0, saved.getCgstAmount());
        assertEquals(9.0, saved.getSgstAmount());
        assertEquals(18.0, saved.getCgstAmount() + saved.getSgstAmount());
        assertEquals(8.0, saved.getDiscountAmount());
        assertEquals(240.0, saved.getTotalAmount());
        assertEquals("29ABCDE1234F1Z5", saved.getRestaurantGstin());
        assertNotNull(saved.getIssuedAt());
        assertEquals("INV-" + Year.now().getValue() + "-00000042", saved.getInvoiceNumber());
        assertEquals("ORD-42", response.getOrderNumber());
    }

    @Test
    void generateOnDelivery_missingOrderAmounts_fallsBackToCalculatedValues() {
        Order order = deliveredOrder();
        order.setTaxAmount(null);
        order.setTotalAmount(null);
        when(orderInvoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(invoicePdfRenderer.render(any(OrderInvoice.class))).thenReturn(PDF);
        stubSave();

        service.generateOnDelivery(order);

        // The invoice is a legal document: it must carry a tax figure even when the order row is
        // incomplete, so the values are derived rather than left null.
        assertNotNull(saved.getTaxAmount());
        assertTrue(saved.getTaxAmount() > 0.0);
        assertEquals(saved.getSubtotal() + saved.getDeliveryFee() + saved.getTaxAmount()
                - saved.getDiscountAmount(), saved.getTotalAmount());
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    void generateOnDelivery_existingInvoice_returnsItWithoutRenderingOrEmailing() {
        OrderInvoice existing = new OrderInvoice();
        existing.setId(9L);
        existing.setInvoiceNumber("INV-2024-00000042");
        existing.setOrder(deliveredOrder());
        when(orderInvoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(existing));

        OrderInvoiceResponse response = service.generateOnDelivery(deliveredOrder());

        assertEquals("INV-2024-00000042", response.getInvoiceNumber());
        // A replayed delivery event must not mail the customer a second copy or burn a render.
        verify(invoicePdfRenderer, never()).render(any(OrderInvoice.class));
        verify(orderInvoiceRepository, never()).save(any(OrderInvoice.class));
        verifyNoInteractions(resilientEmailSender);
    }

    // ---------------------------------------------------------------- storage

    @Test
    void generateOnDelivery_storesPdfAndRecordsKey() {
        when(orderInvoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(invoicePdfRenderer.render(any(OrderInvoice.class))).thenReturn(PDF);
        when(invoicePdfStorageService.store(anyString(), eq(PDF))).thenReturn("invoices/2024/05/x.pdf");
        when(invoicePdfStorageService.presignedUrl("invoices/2024/05/x.pdf"))
                .thenReturn("https://cdn.example.com/x.pdf");
        stubSave();

        OrderInvoiceResponse response = service.generateOnDelivery(deliveredOrder());

        assertEquals("invoices/2024/05/x.pdf", saved.getPdfStorageKey());
        assertNotNull(saved.getPdfGeneratedAt());
        assertTrue(response.getPdfAvailable());
        assertEquals("https://cdn.example.com/x.pdf", response.getPdfUrl());
        // Two writes: the snapshot, then the PDF/email metadata.
        verify(orderInvoiceRepository, times(2)).save(any(OrderInvoice.class));
    }

    @Test
    void generateOnDelivery_storageDisabled_leavesInvoiceUsableWithoutPdfLink() {
        when(orderInvoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(invoicePdfRenderer.render(any(OrderInvoice.class))).thenReturn(PDF);
        // store() returns null when S3 is not configured; it never throws.
        when(invoicePdfStorageService.store(anyString(), eq(PDF))).thenReturn(null);
        stubSave();

        OrderInvoiceResponse response = service.generateOnDelivery(deliveredOrder());

        assertNull(saved.getPdfStorageKey());
        assertFalse(response.getPdfAvailable());
        assertNull(response.getPdfUrl());
        verify(invoicePdfStorageService, never()).presignedUrl(anyString());
    }

    @Test
    void generateOnDelivery_rendererFailure_keepsInvoiceAndSkipsSideEffects() {
        when(orderInvoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(invoicePdfRenderer.render(any(OrderInvoice.class)))
                .thenThrow(new IllegalStateException("font missing"));
        stubSave();

        OrderInvoiceResponse response = assertDoesNotThrow(
                () -> service.generateOnDelivery(deliveredOrder()));

        // The delivery transaction must survive a broken PDF pipeline. The row stays, the optional
        // columns stay empty, and the PDF remains re-renderable later.
        assertNotNull(response.getInvoiceNumber());
        assertFalse(response.getPdfAvailable());
        verify(orderInvoiceRepository, times(1)).save(any(OrderInvoice.class));
        verifyNoInteractions(invoicePdfStorageService);
        verifyNoInteractions(resilientEmailSender);
    }

    // ---------------------------------------------------------------- email

    @Test
    void email_disabledByConfiguration_doesNotSendOrCountAnAttempt() {
        when(orderInvoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(invoicePdfRenderer.render(any(OrderInvoice.class))).thenReturn(PDF);
        stubSave();

        // Email is off by default, so a fresh deployment logs instead of mailing.
        assertFalse(notificationProperties.getEmail().isEnabled());

        service.generateOnDelivery(deliveredOrder());

        verifyNoInteractions(resilientEmailSender);
        assertEquals(0, saved.getEmailAttempts());
        assertNull(saved.getEmailedAt());
        assertNull(saved.getEmailRecipient());
    }

    @Test
    void email_noRecipient_isSkippedWithoutAnAttempt() throws Exception {
        Order order = deliveredOrder();
        order.getCustomer().setEmail(null);
        enableEmail();
        when(orderInvoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(invoicePdfRenderer.render(any(OrderInvoice.class))).thenReturn(PDF);
        stubSave();

        service.generateOnDelivery(order);

        verify(resilientEmailSender, never()).sendWithAttachment(
                anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
        assertEquals(0, saved.getEmailAttempts());
    }

    @Test
    void email_sent_recordsTimestampRecipientAndAttempt() throws Exception {
        enableEmail();
        when(orderInvoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(invoicePdfRenderer.render(any(OrderInvoice.class))).thenReturn(PDF);
        when(resilientEmailSender.sendWithAttachment(
                anyString(), eq(EMAIL), anyString(), anyString(), anyString(), eq(PDF), anyString()))
                .thenReturn(true);
        stubSave();

        OrderInvoiceResponse response = service.generateOnDelivery(deliveredOrder());

        assertEquals(1, saved.getEmailAttempts());
        assertNotNull(saved.getEmailedAt());
        assertEquals(EMAIL, saved.getEmailRecipient());
        assertNotNull(response.getEmailedAt());
    }

    @Test
    void email_droppedByProvider_countsAttemptButLeavesInvoiceUnmailed() throws Exception {
        enableEmail();
        when(orderInvoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(invoicePdfRenderer.render(any(OrderInvoice.class))).thenReturn(PDF);
        // false = circuit breaker open or provider refused; not an exception.
        when(resilientEmailSender.sendWithAttachment(
                anyString(), eq(EMAIL), anyString(), anyString(), anyString(), eq(PDF), anyString()))
                .thenReturn(false);
        stubSave();

        OrderInvoiceResponse response = service.generateOnDelivery(deliveredOrder());

        assertEquals(1, saved.getEmailAttempts());
        // Left null on purpose: this is the signal a retry job looks for.
        assertNull(saved.getEmailedAt());
        assertNull(saved.getEmailRecipient());
        assertNull(response.getEmailedAt());
    }

    @Test
    void email_senderThrows_isSwallowedAndInvoiceStillSaved() throws Exception {
        enableEmail();
        when(orderInvoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(invoicePdfRenderer.render(any(OrderInvoice.class))).thenReturn(PDF);
        when(resilientEmailSender.sendWithAttachment(
                anyString(), eq(EMAIL), anyString(), anyString(), anyString(), eq(PDF), anyString()))
                .thenThrow(new IllegalStateException("JavaMailSender is not configured"));
        stubSave();

        assertDoesNotThrow(() -> service.generateOnDelivery(deliveredOrder()));

        assertEquals(1, saved.getEmailAttempts());
        assertNull(saved.getEmailedAt());
        verify(orderInvoiceRepository, times(2)).save(any(OrderInvoice.class));
    }

    // ---------------------------------------------------------------- download

    @Test
    void renderPdf_missingInvoice_reportsNotFound() {
        when(orderInvoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.renderPdf(ORDER_ID));

        assertEquals("Invoice not found for order", ex.getMessage());
        verifyNoInteractions(invoicePdfRenderer);
    }

    @Test
    void renderPdf_warmsOrderGraphBeforeRendering() {
        OrderInvoice invoice = new OrderInvoice();
        invoice.setOrder(deliveredOrder());
        when(orderInvoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(invoice));
        when(invoicePdfRenderer.render(invoice)).thenReturn(PDF);

        byte[] pdf = service.renderPdf(ORDER_ID);

        assertArrayEquals(PDF, pdf);
        // open-in-view is disabled, so the lazy graph the renderer walks must be JOIN FETCHed here
        // rather than lazily triggered mid-render.
        verify(orderRepository).findByIdWithDetails(ORDER_ID);
    }

    @Test
    void pdfFileName_isDeterministicAndZeroPadded() {
        assertEquals("INV-" + Year.now().getValue() + "-00000042.pdf", service.pdfFileName(ORDER_ID));
    }
}
