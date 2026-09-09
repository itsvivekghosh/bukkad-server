package com.bhukkad.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end contract check for the order-saga money surface (batch A) on the
 * real PostgreSQL schema: {@code POST /api/v1/internal/payments/charge} and
 * {@code POST /api/v1/internal/payments/{paymentId}/refund}, behind the
 * platform-lib ServiceJwtAuthFilter. Also round-trips the internal delivery
 * (COD wallet / rider earnings) surface that V2 repaired.
 */
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.auth.service.jwt-secret=" + InternalPaymentApiPostgresIntegrationTest.SERVICE_SECRET,
                "app.auth.service.allowed-services=order"
        })
class InternalPaymentApiPostgresIntegrationTest extends AbstractPaymentPostgresTest {

    static final String SERVICE_SECRET = "batch-b-saga-secret-0123456789abcdef-32+";

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient restClient;

    private record HttpResult(int status, String body) {
    }

    @BeforeEach
    void setUpAndClean() {
        restClient = RestClient.create("http://localhost:" + port);
        jdbcTemplate.update("DELETE FROM dunning_runs");
        jdbcTemplate.update("DELETE FROM disputes");
        jdbcTemplate.update("DELETE FROM rider_earnings");
        jdbcTemplate.update("DELETE FROM agent_cod_wallets");
        jdbcTemplate.update("DELETE FROM restaurant_settlements");
        jdbcTemplate.update("DELETE FROM settlement_runs");
        jdbcTemplate.update("DELETE FROM wallet_transactions");
        jdbcTemplate.update("DELETE FROM wallet_balances");
        jdbcTemplate.update("DELETE FROM idempotency_records");
        jdbcTemplate.update("DELETE FROM payments");
    }

    private String serviceToken() {
        SecretKey key = Keys.hmacShaKeyFor(SERVICE_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("order")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(key)
                .compact();
    }

    private HttpResult post(String path, Object jsonBody) {
        return post(path, jsonBody, serviceToken());
    }

    private HttpResult post(String path, Object jsonBody, String token) {
        RestClient.RequestBodySpec spec = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec = spec.header("X-Service-Token", token);
        }
        if (jsonBody != null) {
            spec = spec.body(jsonBody);
        }
        try {
            var entity = spec.retrieve().toEntity(String.class);
            return new HttpResult(entity.getStatusCode().value(), entity.getBody());
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            return new HttpResult(ex.getStatusCode().value(), ex.getResponseBodyAsString());
        }
    }

    private long paymentIdOf(String body) throws Exception {
        return objectMapper.readTree(body).path("paymentId").asLong();
    }

    private Map<String, Object> chargeBody(String method, Object amount, String reference) {
        return Map.of("orderId", 55L, "customerId", 7L, "amount", amount,
                "paymentMethod", method, "reference", reference);
    }

    @Test
    void charge_nonWallet_isIdempotentOnReferenceAndNeverMovesMoney() throws Exception {
        HttpResult first = post("/api/v1/internal/payments/charge",
                chargeBody("UPI", 250.00, "saga-ref-1"));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body()).contains("\"status\":\"CHARGED\"");
        long id = paymentIdOf(first.body());

        HttpResult replay = post("/api/v1/internal/payments/charge",
                chargeBody("UPI", "250.00", "saga-ref-1")); // string decimal also accepted
        assertThat(replay.status()).isEqualTo(200);
        assertThat(paymentIdOf(replay.body())).isEqualTo(id);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payments WHERE idempotency_key = 'saga-ref-1'", Integer.class))
                .isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, provider, provider_ref, payment_method, amount FROM payments WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("PROCESSING");
        assertThat(row.get("provider")).isEqualTo("SIMULATED");
        assertThat(row.get("provider_ref")).isEqualTo("SIMULATED-" + id);
        assertThat(((Number) row.get("amount")).doubleValue()).isEqualTo(250.0);
        // Charge never touches wallets (the saga debits separately).
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM wallet_transactions", Integer.class))
                .isZero();
    }

    @Test
    void charge_wallet_marksPendingWalletWithoutDebit() throws Exception {
        HttpResult result = post("/api/v1/internal/payments/charge",
                chargeBody("WALLET", "99.00", "saga-ref-w"));
        assertThat(result.status()).isEqualTo(200);
        long id = paymentIdOf(result.body());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, id))
                .isEqualTo("PENDING_WALLET");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_ref FROM payments WHERE id = ?", String.class, id)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM wallet_transactions", Integer.class))
                .isZero();
    }

    @Test
    void charge_rejectsInvalidInputs() {
        assertThat(post("/api/v1/internal/payments/charge",
                chargeBody("BITCOIN", 10.00, "bad-method")).status()).isEqualTo(400);
        assertThat(post("/api/v1/internal/payments/charge",
                chargeBody("UPI", -5, "bad-amount")).status()).isEqualTo(400);
        assertThat(post("/api/v1/internal/payments/charge",
                Map.of("customerId", 7L, "amount", 10, "paymentMethod", "UPI", "reference", "r")).status())
                .isEqualTo(400);
        assertThat(post("/api/v1/internal/payments/charge",
                Map.of("orderId", 1L, "customerId", 7L, "amount", 10, "paymentMethod", "UPI"))
                .status()).isEqualTo(400);
    }

    @Test
    void refund_marksRefunded_andWalletCompensationRunsExactlyOnce() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO wallet_balances (customer_id, balance, updated_at) " +
                        "VALUES (7, 50.00, localtimestamp)");
        Long paymentId = jdbcTemplate.queryForObject(
                "INSERT INTO payments (order_id, customer_id, amount, currency, status, " +
                        "payment_method, purpose, wallet_amount, gateway_amount, created_at, updated_at) " +
                        "VALUES (55, 7, 100.00, 'INR', 'SETTLED', 'WALLET', 'ORDER', 100.00, 0, " +
                        "localtimestamp, localtimestamp) RETURNING id", Long.class);

        HttpResult first = post("/api/v1/internal/payments/" + paymentId + "/refund",
                Map.of("reason", "order cancelled"));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body()).contains("\"status\":\"REFUNDED\"");
        assertThat(paymentIdOf(first.body())).isEqualTo(paymentId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, paymentId))
                .isEqualTo("REFUNDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM wallet_balances WHERE customer_id = 7", java.math.BigDecimal.class))
                .isEqualByComparingTo("150.00");
        // M-1 on the compensation row: ledger equals real remaining balance.
        assertThat(jdbcTemplate.queryForList(
                "SELECT balance_after FROM wallet_transactions WHERE customer_id = 7",
                java.math.BigDecimal.class))
                .hasSize(1)
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo("150.00"));

        HttpResult replay = post("/api/v1/internal/payments/" + paymentId + "/refund",
                Map.of("reason", "order cancelled"));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body()); // same response on replay
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM wallet_balances WHERE customer_id = 7", java.math.BigDecimal.class))
                .isEqualByComparingTo("150.00"); // no double credit
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallet_transactions WHERE customer_id = 7", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void refund_pendingWalletCharge_compensatesNothing() throws Exception {
        HttpResult charged = post("/api/v1/internal/payments/charge",
                chargeBody("WALLET", 80.00, "saga-ref-abort"));
        long paymentId = paymentIdOf(charged.body());

        HttpResult refund = post("/api/v1/internal/payments/" + paymentId + "/refund",
                Map.of("reason", "saga abort"));
        assertThat(refund.status()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, paymentId))
                .isEqualTo("REFUNDED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM wallet_transactions", Integer.class))
                .isZero();
    }

    @Test
    void refund_unknownPayment_returns404() {
        HttpResult result = post("/api/v1/internal/payments/999999/refund",
                Map.of("reason", "ghost"));
        assertThat(result.status()).isEqualTo(404);
    }

    @Test
    void internalEndpoints_demandServiceToken() {
        HttpResult anonymous = post("/api/v1/internal/payments/charge",
                chargeBody("UPI", 10.00, "no-token"), null);
        assertThat(anonymous.status()).isEqualTo(401);

        HttpResult badToken = post("/api/v1/internal/payments/charge",
                chargeBody("UPI", 10.00, "bad-token"), "not-a-jwt");
        assertThat(badToken.status()).isEqualTo(401);
    }

    @Test
    void migratedDeliverySurface_roundTripsOnPostgres() {
        // V2 repair: agent_cod_wallets.balance/updated_at must exist for these
        // internal calls to work against production-style (flyway-only) schema.
        HttpResult credit = post("/api/v1/internal/delivery/cod-wallet/9/credit?amount=250.00", null);
        assertThat(credit.status()).isEqualTo(200);
        assertThat(credit.body()).contains("\"balance\":250.0");

        HttpResult earning = post("/api/v1/internal/delivery/earnings/record?agentId=9&orderId=100&amount=49.50", null);
        assertThat(earning.status()).isEqualTo(200);
        assertThat(earning.body()).contains("\"status\":\"EARNED\"");

        HttpResult duplicate = post("/api/v1/internal/delivery/earnings/record?agentId=9&orderId=100&amount=49.50", null);
        assertThat(duplicate.status()).isEqualTo(200);
        assertThat(duplicate.body()).contains("\"duplicate\":true");
    }
}
