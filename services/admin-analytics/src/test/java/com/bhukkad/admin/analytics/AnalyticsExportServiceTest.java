package com.bhukkad.admin.analytics;
import com.bhukkad.admin.domain.service.AnalyticsExportService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Unit tests for {@link AnalyticsExportService} CSV streaming.
 * <p>The {@link JdbcTemplate} is mocked; the {@code RowCallbackHandler} that the
 * service registers is captured and invoked with a {@link Proxy}-based
 * {@link ResultSet} stub so the CSV output can be asserted without a database.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AnalyticsExportServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private static final Timestamp NOW = Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 10, 30));

    @Test
    void streamOrdersCsv_writesHeaderAndRow() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        AnalyticsExportService service = new AnalyticsExportService(jdbcTemplate);
        RowCallbackHandler captured = capture();
        service.streamOrdersCsv(pw, "2026-01-01", "2026-01-02");
        captured.processRow(stubRs("id", "123", "order_number", "ORD-ABC", "total_amount", "250.00"));
        pw.flush();
        String csv = sw.toString();
        assertTrue(csv.contains("Order ID,Order Number,Status,Total Amount"));
        assertTrue(csv.contains("ORD-ABC"));
        assertTrue(csv.contains("123"));
        assertTrue(csv.contains("250.00"));
    }

    @Test
    void streamRestaurantsCsv_writesHeader() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        AnalyticsExportService service = new AnalyticsExportService(jdbcTemplate);
        RowCallbackHandler captured = capture();
        service.streamRestaurantsCsv(pw, null);
        captured.processRow(stubRs("id", "55", "name", "Taj"));
        pw.flush();
        String csv = sw.toString();
        assertTrue(csv.contains("Restaurant ID,Name,Address,Phone,Is Active"));
        assertTrue(csv.contains("Taj"));
    }

    @Test
    void streamRidersCsv_writesHeader() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        AnalyticsExportService service = new AnalyticsExportService(jdbcTemplate);
        RowCallbackHandler captured = capture();
        service.streamRidersCsv(pw, null);
        captured.processRow(stubRs("id", "77", "name", "Rider One"));
        pw.flush();
        String csv = sw.toString();
        assertTrue(csv.contains("Rider ID,Full Name,Phone,Vehicle Type,Vehicle Number"));
        assertTrue(csv.contains("Rider One"));
    }

    @Test
    void streamPaymentsCsv_writesHeader() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        AnalyticsExportService service = new AnalyticsExportService(jdbcTemplate);
        RowCallbackHandler captured = capture();
        service.streamPaymentsCsv(pw, null, null, null);
        captured.processRow(stubRs("id", "999", "provider_ref", "PAY-1"));
        pw.flush();
        String csv = sw.toString();
        assertTrue(csv.contains("Payment ID,Gateway Payment ID,Order ID"));
        assertTrue(csv.contains("PAY-1"));
    }

    @Test
    void escapeCsv_quotesFieldsWithCommas() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        AnalyticsExportService service = new AnalyticsExportService(jdbcTemplate);
        RowCallbackHandler captured = capture();
        service.streamOrdersCsv(pw, null, null);
        captured.processRow(stubRs("restaurant_name", "Doe, John"));
        pw.flush();
        String csv = sw.toString();
        assertTrue(csv.contains("\"Doe, John\""));
    }

    @Test
    void writeOrderRow_withNullFields_writesEmpty() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        AnalyticsExportService service = new AnalyticsExportService(jdbcTemplate);
        RowCallbackHandler captured = capture();
        service.streamOrdersCsv(pw, null, null);
        ResultSet rs = (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{ResultSet.class},
                (proxy, method, args) -> {
                    if ("getString".equals(method.getName())) return null;
                    if ("getTimestamp".equals(method.getName())) return null;
                    return null;
                });
        captured.processRow(rs);
        pw.flush();
        String csv = sw.toString();
        String[] lines = csv.split("\n");
        assertTrue(lines.length >= 2, "Should have header + at least one data row");
        // Header and one data row only; nulls render as empty cells (still comma separated)
        assertTrue(csv.lines().skip(1).findFirst().orElse("").contains(",,"));
    }

    @Test
    void streamOrdersCsv_rejectsMalformedDatesBeforeQuery() {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        AnalyticsExportService service = new AnalyticsExportService(jdbcTemplate);
        String maliciousFrom = "2026-01-01' OR '1'='1";
        String maliciousTo = "2026-12-31' --";

        // Post-audit: user-supplied dates are PARSED to LocalDateTime before any
        // SQL runs, so injection payloads and malformed values are rejected with
        // a 400 (BusinessException) — the query is never reached.
        assertThatThrownBy(() -> service.streamOrdersCsv(pw, maliciousFrom, maliciousTo))
                .isInstanceOf(com.bhukkad.common.error.BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void streamOrdersCsv_bindsValidDatesAsParameters() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        AnalyticsExportService service = new AnalyticsExportService(jdbcTemplate);
        String validFrom = "2026-01-01T00:00:00";
        String validTo = "2026-12-31T23:59:59";
        service.streamOrdersCsv(pw, validFrom, validTo);
        pw.flush();

        org.mockito.ArgumentCaptor<String> sqlCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> paramsCaptor =
                org.mockito.ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbcTemplate).query(
                sqlCaptor.capture(), any(RowCallbackHandler.class), paramsCaptor.capture());

        assertEquals(2, paramsCaptor.getValue().length);
        assertThat(paramsCaptor.getValue()[0]).isInstanceOf(java.time.LocalDateTime.class);
        assertThat(paramsCaptor.getValue()[1]).isInstanceOf(java.time.LocalDateTime.class);
    }

    @Test
    void streamPaymentsCsv_bindsStatusAsParameter() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        AnalyticsExportService service = new AnalyticsExportService(jdbcTemplate);
        String maliciousStatus = "COMPLETED' OR '1'='1";
        service.streamPaymentsCsv(pw, null, null, maliciousStatus);
        pw.flush();

        org.mockito.ArgumentCaptor<String> sqlCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> paramsCaptor =
                org.mockito.ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbcTemplate).query(
                sqlCaptor.capture(), any(RowCallbackHandler.class), paramsCaptor.capture());

        assertFalse(sqlCaptor.getValue().contains("OR '1'='1"));
        assertEquals(1, paramsCaptor.getValue().length);
        assertEquals(maliciousStatus, paramsCaptor.getValue()[0]);
    }

    // ---- helpers ----

    /** Sets up the mock to capture the RowCallbackHandler and returns it. */
    private RowCallbackHandler capture() {
        final RowCallbackHandler[] captured = new RowCallbackHandler[1];
        // The exports mix the params and no-params JdbcTemplate.query overloads
        // (per-CSV), so both are stubbed; class-level LENIENT strictness allows
        // whichever overload a given CSV does not use.
        doAnswer(invocation -> {
            captured[0] = invocation.getArgument(1);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        doAnswer(invocation -> {
            captured[0] = invocation.getArgument(1);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));
        return new RowCallbackHandler() {
            @Override
            public void processRow(ResultSet rs) throws java.sql.SQLException {
                captured[0].processRow(rs);
            }
        };
    }

    /** Convenience overload to keep call sites compact (unused helper avoided). */

    /** Builds a {@link ResultSet} proxy from alternating key-value pairs. */
    private ResultSet stubRs(String... keyValuePairs) {
        var map = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getString".equals(name) && args != null && args.length == 1) {
                        return map.getOrDefault(args[0], "v");
                    }
                    if ("getTimestamp".equals(name)) {
                        return NOW;
                    }
                    if ("toString".equals(name)) return "ResultSetStub";
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) return proxy == args[0];
                    Class<?> ret = method.getReturnType();
                    if (ret == int.class || ret == long.class) return 0;
                    if (ret == double.class) return 0.0;
                    if (ret == boolean.class) return false;
                    return null;
                });
    }
}