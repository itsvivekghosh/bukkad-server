package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.OrderInvoiceResponse;
import com.bhukkad.invoice.OrderInvoiceService;
import com.bhukkad.timeline.OrderTimelineService;
import com.bhukkad.delivery.RiderLocationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderGrowthControllerTest {

    @Mock
    private OrderTimelineService orderTimelineService;
    @Mock
    private OrderInvoiceService orderInvoiceService;
    @Mock
    private RiderLocationService riderLocationService;

    @InjectMocks
    private OrderGrowthController controller;

    @Test
    void downloadOrderInvoicePdf_returnsPdfBytesWithAttachmentDisposition() {
        byte[] pdf = new byte[] {0x25, 0x50, 0x44, 0x46};
        when(orderInvoiceService.renderPdf(42L)).thenReturn(pdf);
        when(orderInvoiceService.pdfFileName(42L)).thenReturn("invoice-42.pdf");

        ResponseEntity<byte[]> response = controller.downloadOrderInvoicePdf(42L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertArrayEquals(pdf, response.getBody());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("invoice-42.pdf"));
        verify(orderInvoiceService).renderPdf(42L);
    }

    @Test
    void getOrderInvoice_returnsJsonEnvelope() {
        OrderInvoiceResponse invoice = OrderInvoiceResponse.builder().orderId(42L).build();
        when(orderInvoiceService.getByOrderId(42L)).thenReturn(invoice);

        ResponseEntity<ApiResponse<OrderInvoiceResponse>> response = controller.getOrderInvoice(42L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(invoice, response.getBody().getData());
    }
}
