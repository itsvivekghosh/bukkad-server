package com.bhukkad.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
public class AnalyticsExportService {
    private static final String COL_CREATED_AT = "created_at";
    private static final String COL_UPDATED_AT = "updated_at";
    private static final String COL_COMPLETED_AT = "completed_at";


    private final JdbcTemplate jdbcTemplate;

    private static final DateTimeFormatter CSV_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public void streamOrdersCsv(java.io.PrintWriter writer, String fromDate, String toDate) {
        StringBuilder sql = new StringBuilder("""
            SELECT o.id, o.order_number, o.status, o.total_amount, o.created_at,
                   c.name as customer_name, c.phone as customer_phone,
                   r.name as restaurant_name, r.city as restaurant_city,
                   d.full_name as rider_name, d.phone as rider_phone
            FROM orders o
            JOIN customers c ON o.customer_id = c.id
            JOIN restaurants r ON o.restaurant_id = r.id
            LEFT JOIN delivery_agents d ON o.delivery_agent_id = d.id
            WHERE 1=1
            """);

        if (fromDate != null && !fromDate.isEmpty()) {
            sql.append(" AND o.created_at >= '").append(fromDate).append("'");
        }
        if (toDate != null && !toDate.isEmpty()) {
            sql.append(" AND o.created_at <= '").append(toDate).append("'");
        }
        sql.append(" ORDER BY o.created_at DESC");

        writer.println("Order ID,Order Number,Status,Total Amount,Created At,Customer Name,Customer Phone," +
                "Restaurant Name,Restaurant City,Rider Name,Rider Phone");

        jdbcTemplate.query(sql.toString(), (RowCallbackHandler) rs -> writeOrderRow(writer, rs));
    }

    public void streamRestaurantsCsv(java.io.PrintWriter writer, String city) {
        StringBuilder sql = new StringBuilder("""
            SELECT r.id, r.name, r.address, r.city, r.phone, r.is_active, r.is_open,
                   r.opening_time, r.closing_time, r.average_delivery_time,
                   r.commission_rate, r.created_at
            FROM restaurants r
            WHERE 1=1
            """);

        if (city != null && !city.isEmpty()) {
            sql.append(" AND r.city = '").append(city.replace("'", "''")).append("'");
        }
        sql.append(" ORDER BY r.name");

        writer.println("Restaurant ID,Name,Address,City,Phone,Is Active,Is Open,Opening Time,Closing Time," +
                "Average Delivery Time,Commission Rate,Created At");

        jdbcTemplate.query(sql.toString(), (RowCallbackHandler) rs -> writeRestaurantRow(writer, rs));
    }

    public void streamRidersCsv(java.io.PrintWriter writer, String city) {
        StringBuilder sql = new StringBuilder("""
            SELECT d.id, d.full_name, d.phone, d.email, d.vehicle_type, d.license_plate,
                   d.is_active, d.city, d.current_latitude, d.current_longitude,
                   d.created_at, d.updated_at
            FROM delivery_agents d
            WHERE 1=1
            """);

        if (city != null && !city.isEmpty()) {
            sql.append(" AND d.city = '").append(city.replace("'", "''")).append("'");
        }
        sql.append(" ORDER BY d.full_name");

        writer.println("Rider ID,Full Name,Phone,Email,Vehicle Type,License Plate,Is Active,City," +
                "Current Latitude,Current Longitude,Created At,Updated At");

        jdbcTemplate.query(sql.toString(), (RowCallbackHandler) rs -> writeRiderRow(writer, rs));
    }

    public void streamPaymentsCsv(java.io.PrintWriter writer, String fromDate, String toDate, String status) {
        StringBuilder sql = new StringBuilder("""
            SELECT p.id, p.payment_id, p.order_id, p.amount, p.payment_method, p.status,
                   p.gateway, p.transaction_id, p.created_at, p.completed_at,
                   o.order_number, c.name as customer_name
            FROM payments p
            JOIN orders o ON p.order_id = o.id
            JOIN customers c ON o.customer_id = c.id
            WHERE 1=1
            """);

        if (fromDate != null && !fromDate.isEmpty()) {
            sql.append(" AND p.created_at >= '").append(fromDate).append("'");
        }
        if (toDate != null && !toDate.isEmpty()) {
            sql.append(" AND p.created_at <= '").append(toDate).append("'");
        }
        if (status != null && !status.isEmpty()) {
            sql.append(" AND p.status = '").append(status.replace("'", "''")).append("'");
        }
        sql.append(" ORDER BY p.created_at DESC");

        writer.println("Payment ID,Gateway Payment ID,Order ID,Amount,Payment Method,Status,Gateway," +
                "Gateway Transaction ID,Created At,Completed At,Order Number,Customer Name");

        jdbcTemplate.query(sql.toString(), (RowCallbackHandler) rs -> writePaymentRow(writer, rs));
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
        writer.print(",");
        writer.print(escapeCsv(rs.getString("customer_name")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("customer_phone")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("restaurant_name")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("restaurant_city")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("rider_name")));
        writer.print(",");
        writer.println(escapeCsv(rs.getString("rider_phone")));
    }

    private void writeRestaurantRow(PrintWriter writer, ResultSet rs) throws SQLException {
        writer.print(escapeCsv(rs.getString("id")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("name")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("address")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("city")));
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
        writer.print(escapeCsv(rs.getString("commission_rate")));
        writer.print(",");
        writer.println(escapeCsv(formatTimestamp(rs.getTimestamp(COL_CREATED_AT))));
    }

    private void writeRiderRow(PrintWriter writer, ResultSet rs) throws SQLException {
        writer.print(escapeCsv(rs.getString("id")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("full_name")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("phone")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("email")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("vehicle_type")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("license_plate")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("is_active")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("city")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("current_latitude")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("current_longitude")));
        writer.print(",");
        writer.print(escapeCsv(formatTimestamp(rs.getTimestamp(COL_CREATED_AT))));
        writer.print(",");
        writer.println(escapeCsv(formatTimestamp(rs.getTimestamp(COL_UPDATED_AT))));
    }

    private void writePaymentRow(PrintWriter writer, ResultSet rs) throws SQLException {
        writer.print(escapeCsv(rs.getString("id")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("payment_id")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("order_id")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("amount")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("payment_method")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("status")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("gateway")));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("transaction_id")));
        writer.print(",");
        writer.print(escapeCsv(formatTimestamp(rs.getTimestamp(COL_CREATED_AT))));
        writer.print(",");
        writer.print(escapeCsv(formatTimestamp(rs.getTimestamp(COL_COMPLETED_AT))));
        writer.print(",");
        writer.print(escapeCsv(rs.getString("order_number")));
        writer.print(",");
        writer.println(escapeCsv(rs.getString("customer_name")));
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    private String formatTimestamp(java.sql.Timestamp timestamp) {
        if (timestamp == null) {
            return "";
        }
        return timestamp.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}