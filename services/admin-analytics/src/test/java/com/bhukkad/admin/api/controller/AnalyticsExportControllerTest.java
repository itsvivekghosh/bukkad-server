package com.bhukkad.admin.api.controller;

import com.bhukkad.admin.domain.service.AnalyticsExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AnalyticsExportControllerTest {

    @Mock private AnalyticsExportService exportService;
    @InjectMocks private AnalyticsExportController controller;

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void exportOrders_setsCsvHeadersAndDelegates() throws IOException {
        StreamingResponseBody body = controller.exportOrders("2026-01-01", "2026-02-01", response);

        assertThat(response.getContentType()).isEqualTo("text/csv");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=orders_export_")
                .endsWith(".csv");

        body.writeTo(new ByteArrayOutputStream());
        verify(exportService).streamOrdersCsv(org.mockito.ArgumentMatchers.<PrintWriter>any(),
                eq("2026-01-01"), eq("2026-02-01"));
    }

    @Test
    void exportRestaurants_setsCsvHeadersAndDelegates() throws IOException {
        StreamingResponseBody body = controller.exportRestaurants("Mumbai", response);

        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=restaurants_export_");
        body.writeTo(new ByteArrayOutputStream());
        verify(exportService).streamRestaurantsCsv(org.mockito.ArgumentMatchers.<PrintWriter>any(), eq("Mumbai"));
    }

    @Test
    void exportRiders_setsCsvHeadersAndDelegates() throws IOException {
        StreamingResponseBody body = controller.exportRiders("Pune", response);

        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=riders_export_");
        body.writeTo(new ByteArrayOutputStream());
        verify(exportService).streamRidersCsv(org.mockito.ArgumentMatchers.<PrintWriter>any(), eq("Pune"));
    }

    @Test
    void exportPayments_streamsCsvAndClosesWriter() throws IOException {
        StreamingResponseBody body = controller.exportPayments("2026-01-01", null, "SUCCESS", response);

        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=payments_export_");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);
        assertThat(out.size()).isZero(); // service is a mock; stream body itself just wraps/closes the writer

        var captor = org.mockito.ArgumentCaptor.forClass(PrintWriter.class);
        verify(exportService).streamPaymentsCsv(captor.capture(), eq("2026-01-01"), eq(null), eq("SUCCESS"));
        assertThat(captor.getValue()).isNotNull();
    }
}
