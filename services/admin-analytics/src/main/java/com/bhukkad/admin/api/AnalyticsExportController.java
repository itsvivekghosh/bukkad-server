package com.bhukkad.admin.api;

import com.bhukkad.admin.analytics.AnalyticsExportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Controller for streaming large analytics exports.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AnalyticsExportController {

    private static final String CSV_CONTENT_TYPE = "text/csv";
    private static final String CONTENT_DISPOSITION_HEADER = "Content-Disposition";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");
    private static final String CSV_EXTENSION = ".csv";

    private final AnalyticsExportService exportService;

    @GetMapping("/export/orders")
    public StreamingResponseBody exportOrders(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            HttpServletResponse response
    ) {
        prepareCsvDownload(response, "orders_export_");

        return outputStream -> {
            try (PrintWriter writer = new PrintWriter(outputStream)) {
                exportService.streamOrdersCsv(writer, fromDate, toDate);
            }
        };
    }

    @GetMapping("/export/restaurants")
    public StreamingResponseBody exportRestaurants(
            @RequestParam(required = false) String city,
            HttpServletResponse response
    ) {
        prepareCsvDownload(response, "restaurants_export_");

        return outputStream -> {
            try (PrintWriter writer = new PrintWriter(outputStream)) {
                exportService.streamRestaurantsCsv(writer, city);
            }
        };
    }

    @GetMapping("/export/riders")
    public StreamingResponseBody exportRiders(
            @RequestParam(required = false) String city,
            HttpServletResponse response
    ) {
        prepareCsvDownload(response, "riders_export_");

        return outputStream -> {
            try (PrintWriter writer = new PrintWriter(outputStream)) {
                exportService.streamRidersCsv(writer, city);
            }
        };
    }

    @GetMapping("/export/payments")
    public StreamingResponseBody exportPayments(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String status,
            HttpServletResponse response
    ) {
        prepareCsvDownload(response, "payments_export_");

        return outputStream -> {
            try (PrintWriter writer = new PrintWriter(outputStream)) {
                exportService.streamPaymentsCsv(writer, fromDate, toDate, status);
            }
        };
    }

    private void prepareCsvDownload(HttpServletResponse response, String filenamePrefix) {
        response.setContentType(CSV_CONTENT_TYPE);
        response.setHeader(CONTENT_DISPOSITION_HEADER,
                "attachment; filename=" + filenamePrefix
                        + LocalDateTime.now().format(TIMESTAMP_FORMAT) + CSV_EXTENSION);
    }
}