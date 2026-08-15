package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.OrderInvoiceResponse;
import com.bhukkad.dto.response.OrderTimelineEventResponse;
import com.bhukkad.dto.response.RiderLocationResponse;
import com.bhukkad.invoice.OrderInvoiceService;
import com.bhukkad.timeline.OrderTimelineService;
import com.bhukkad.delivery.RiderLocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Order extensions: timeline, GST invoice, live rider location.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/orders")
@RequiredArgsConstructor
public class OrderGrowthController {

    private final OrderTimelineService orderTimelineService;
    private final OrderInvoiceService orderInvoiceService;
    private final RiderLocationService riderLocationService;

    /** Order status timeline for customer support and tracking. */
    @GetMapping("/{orderId}/timeline")
    @PreAuthorize("hasAnyRole('CUSTOMER','RESTAURANT_OWNER','DELIVERY_AGENT','ADMIN')")
    public ResponseEntity<ApiResponse<List<OrderTimelineEventResponse>>> getOrderTimeline(
            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.success(orderTimelineService.getTimelineForOrder(orderId)));
    }

    /** GST invoice for a delivered order. */
    @GetMapping("/{orderId}/invoice")
    @PreAuthorize("hasAnyRole('CUSTOMER','RESTAURANT_OWNER','ADMIN')")
    public ResponseEntity<ApiResponse<OrderInvoiceResponse>> getOrderInvoice(
            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.success(orderInvoiceService.getByOrderId(orderId)));
    }

    /**
     * Downloads the GST invoice as a PDF file.
     *
     * <p>Rendered on demand from the persisted invoice snapshot rather than
     * proxied from object storage, so the endpoint behaves identically whether or
     * not a stored copy exists. Returns the raw PDF instead of the usual
     * {@code ApiResponse} envelope so browsers and mobile clients can hand the
     * bytes straight to a file viewer.
     */
    @GetMapping("/{orderId}/invoice/pdf")
    @PreAuthorize("hasAnyRole('CUSTOMER','RESTAURANT_OWNER','ADMIN')")
    public ResponseEntity<byte[]> downloadOrderInvoicePdf(@PathVariable Long orderId) {
        byte[] pdf = orderInvoiceService.renderPdf(orderId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(orderInvoiceService.pdfFileName(orderId))
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }

    /** Latest rider GPS location for live map (customer). */
    @GetMapping("/{orderId}/rider-location")
    @PreAuthorize("hasAnyRole('CUSTOMER','RESTAURANT_OWNER','ADMIN')")
    public ResponseEntity<ApiResponse<RiderLocationResponse>> getRiderLocation(
            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.success(riderLocationService.getLatestForOrder(orderId)));
    }
}
