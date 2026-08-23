package com.bhukkad.analytics;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Controller for streaming large analytics exports.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsExportController {

    private final AnalyticsExportService exportService;

    @GetMapping("/export/orders")
    public StreamingResponseBody exportOrders(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            HttpServletResponse response
    ) {
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", 
            "attachment; filename=orders_export_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + ".csv");

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
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition",
            "attachment; filename=restaurants_export_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + ".csv");

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
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition",
            "attachment; filename=riders_export_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + ".csv");

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
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition",
            "attachment; filename=payments_export_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + ".csv");

        return outputStream -> {
            try (PrintWriter writer = new PrintWriter(outputStream)) {
                exportService.streamPaymentsCsv(writer, fromDate, toDate, status);
            }
        };
    }
}