package com.bhukkad.admin.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.PrintWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * Service for streaming large analytics exports as CSV.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsExportService {
    private static final String COL_CREATED_AT = "created_at";
    private static final String COL_UPDATED_AT = "updated_at";
    private static final String COL_COMPLETED_AT = "completed_at";


    private final JdbcTemplate jdbcTemplate;

    private static final DateTimeFormatter CSV_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public void streamOrdersCsv(java.io.PrintWriter writer, String fromDate, String toDate) {
        StringBuilder sql = new StringBuilder("""
            SELECT o.id, o.order_number, o.status, o.total_amount, o.created_at,
                   r.name as restaurant_name
            FROM orders o
            JOIN restaurants r ON o.restaurant_id = r.id
            WHERE 1=1
            """);

        java.util.List<Object> params = new java.util.ArrayList<>();
        java.time.LocalDateTime from = parseDateFilter(fromDate, "fromDate");
        java.time.LocalDateTime to = parseDateFilter(toDate, "toDate");
        if (from != null) {
            sql.append(" AND o.created_at >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND o.created_at <= ?");
            params.add(to);
        }
        sql.append(" ORDER BY o.created_at DESC");

        writer.println("Order ID,Order Number,Status,Total Amount,Created At,Restaurant Name");

        jdbcTemplate.query(sql.toString(), (RowCallbackHandler) rs -> writeOrderRow(writer, rs), params.toArray());
    }

    public void streamRestaurantsCsv(java.io.PrintWriter writer, String city) {
        StringBuilder sql = new StringBuilder("""
            SELECT r.id, r.name, r.address, r.phone, r.is_active, r.is_open,
                   r.opening_time, r.closing_time, r.average_delivery_time,
                   r.commission_percent, r.created_at
            FROM restaurants r
            WHERE 1=1
            """);

        sql.append(" ORDER BY r.name");

        writer.println("Restaurant ID,Name,Address,Phone,Is Active,Is Open,Opening Time,Closing Time," +
                "Average Delivery Time,Commission Rate,Created At");

        jdbcTemplate.query(sql.toString(), (RowCallbackHandler) rs -> writeRestaurantRow(writer, rs));
    }

    public void streamRidersCsv(java.io.PrintWriter writer, String city) {
        // Riders live in the delivery DB; this projection is served from the
        // imported FDW table (id, name, phone, is_active, vehicle_type,
        // vehicle_number, created_at, updated_at).
        StringBuilder sql = new StringBuilder("""
            SELECT d.id, d.name, d.phone, d.vehicle_type, d.vehicle_number,
                   d.is_active, d.created_at, d.updated_at
            FROM delivery_agents d
            WHERE 1=1
            """);

        sql.append(" ORDER BY d.name");

        writer.println("Rider ID,Full Name,Phone,Vehicle Type,Vehicle Number,Is Active,Created At,Updated At");

        jdbcTemplate.query(sql.toString(), (RowCallbackHandler) rs -> writeRiderRow(writer, rs));
    }

    public void streamPaymentsCsv(java.io.PrintWriter writer, String fromDate, String toDate, String status) {
        StringBuilder sql = new StringBuilder("""
            SELECT p.id, p.provider_ref, p.order_id, p.amount, p.status,
                   p.created_at, p.updated_at,
                   o.order_number
            FROM payments p
            JOIN orders o ON p.order_id = o.id
            WHERE 1=1
            """);

        java.util.List<Object> params = new java.util.ArrayList<>();
        java.time.LocalDateTime from = parseDateFilter(fromDate, "fromDate");
        java.time.LocalDateTime to = parseDateFilter(toDate, "toDate");
        if (from != null) {
            sql.append(" AND p.created_at >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND p.created_at <= ?");
            params.add(to);
        }
        if (status != null && !status.isEmpty()) {
            sql.append(" AND p.status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY p.created_at DESC");

        writer.println("Payment ID,Gateway Payment ID,Order ID,Amount,Status,Created At,Updated At,Order Number");

        jdbcTemplate.query(sql.toString(), (RowCallbackHandler) rs -> writePaymentRow(writer, rs), params.toArray());
    }

    private void writeOrderRow(PrintWriter writer, ResultSet rs) throws SQLException {
        writer.print(escapeCsv(rs.getString("id")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("order_number")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("status")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("total_amount")));
        writer.print(",");
        writer.print(escapeCsv(formatTimestamp(rs.getTimestamp(COL_CREATED_AT))));
        writer.println(escapeCsv(rs.getString("restaurant_name")));
    }

    private void writeRestaurantRow(PrintWriter writer, ResultSet rs) throws SQLException {
        writer.print(escapeCsv(rs.getString("id")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("name")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("address")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("phone")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("is_active")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("is_open")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("opening_time")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("closing_time")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("average_delivery_time")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("commission_percent")));
        writer.print(",");
        writer.println(escapeCsv(formatTimestamp(rs.getTimestamp(COL_CREATED_AT))));
    }

    private void writeRiderRow(PrintWriter writer, ResultSet rs) throws SQLException {
        writer.print(escapeCsv(rs.getString("id")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("name")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("phone")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("vehicle_type")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("vehicle_number")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("is_active")));
        writer.print(",");
        writer.print(escapeCsv(formatTimestamp(rs.getTimestamp(COL_CREATED_AT))));
        writer.println(escapeCsv(formatTimestamp(rs.getTimestamp(COL_UPDATED_AT))));
    }

    private void writePaymentRow(PrintWriter writer, ResultSet rs) throws SQLException {
        writer.print(escapeCsv(rs.getString("id")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("provider_ref")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("order_id")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("amount")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("status")));
        writer.print(",");
        writer.print(escapeCsv(formatTimestamp(rs.getTimestamp(COL_CREATED_AT))));
        writer.print(",");
        writer.print(escapeCsv(formatTimestamp(rs.getTimestamp(COL_UPDATED_AT))));
        writer.println(escapeCsv(rs.getString("order_number")));
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        // Formula-injection guard: customer-controlled strings starting with
        // =, +, -, or @ execute as spreadsheet formulas when the exported
        // CSV is opened in Excel/Sheets.
        if (escaped.matches("^[=+\\-@].*")) {
            escaped = "'" + escaped;
        }
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    /**
     * Validates and parses an optional date filter BEFORE any query runs —
     * a malformed value previously threw mid-stream (after the CSV headers)
     * and corrupted the download.
     */
    private java.time.LocalDateTime parseDateFilter(String value, String fieldName) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(value);
        } catch (Exception ignored) {
            try {
                return java.time.LocalDate.parse(value).atStartOfDay();
            } catch (Exception ignoredToo) {
                throw new com.bhukkad.common.error.BusinessException(
                        "Invalid " + fieldName + " (expected ISO date or datetime)");
            }
        }
    }

    private String formatTimestamp(java.sql.Timestamp timestamp) {
        if (timestamp == null) {
            return "";
        }
        return timestamp.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}