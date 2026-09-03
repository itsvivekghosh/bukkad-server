package com.bhukkad.order;

import com.bhukkad.order.domain.Coupon;
import com.bhukkad.order.domain.CouponRepository;
import com.bhukkad.order.domain.CouponUsage;
import com.bhukkad.order.domain.CouponUsageRepository;
import com.bhukkad.order.domain.Dispute;
import com.bhukkad.order.domain.DisputeRepository;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the V5 coupon/dispute migration and repositories against PostgreSQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponDisputeRepositoryPostgresIntegrationTest extends AbstractOrderPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CouponUsageRepository couponUsageRepository;
    @Autowired private DisputeRepository disputeRepository;
    @Autowired private OrderRepository orderRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM coupon_usages");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM disputes");
        jdbcTemplate.update("DELETE FROM order_timeline_events");
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
    }

    @Test
    void migration_v5TablesExist() {
        Integer couponTables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('coupons','coupon_usages','disputes')",
                Integer.class);
        assertThat(couponTables).isEqualTo(3);
    }

    @Test
    void saveAndFindCoupon() {
        Coupon coupon = new Coupon();
        coupon.setCode("WELCOME20");
        coupon.setDescription("20% off first order");
        coupon.setDiscountType(Coupon.DiscountType.PERCENTAGE);
        coupon.setDiscountValue(new BigDecimal("20"));
        coupon.setValidFrom(LocalDateTime.now().minusDays(1));
        coupon.setValidUntil(LocalDateTime.now().plusDays(30));
        coupon.setActive(true);
        coupon.setUsedCount(0);
        Coupon saved = couponRepository.saveAndFlush(coupon);

        assertThat(saved.getId()).isNotNull();
        assertThat(couponRepository.findByCode("WELCOME20")).isPresent();

        // Platform-wide coupon
        List<Coupon> active = couponRepository.findActivePlatformCoupons(LocalDateTime.now());
        assertThat(active).hasSize(1);
    }

    @Test
    void saveCouponUsage() {
        Coupon coupon = new Coupon();
        coupon.setCode("SAVE10");
        coupon.setDescription("10 off");
        coupon.setDiscountType(Coupon.DiscountType.FIXED_AMOUNT);
        coupon.setDiscountValue(new BigDecimal("10"));
        coupon.setValidFrom(LocalDateTime.now().minusDays(1));
        coupon.setValidUntil(LocalDateTime.now().plusDays(30));
        coupon.setActive(true);
        coupon.setUsedCount(0);
        Coupon saved = couponRepository.saveAndFlush(coupon);

        CouponUsage usage = new CouponUsage();
        usage.setCouponId(saved.getId());
        usage.setCustomerId(5L);
        usage.setOrderId(null);
        couponUsageRepository.saveAndFlush(usage);

        assertThat(couponUsageRepository.countByCouponIdAndCustomerId(saved.getId(), 5L)).isEqualTo(1);
    }

    @Test
    void incrementUsedCountAtomic() {
        Coupon coupon = new Coupon();
        coupon.setCode("LIMITED");
        coupon.setDescription("Limited use");
        coupon.setDiscountType(Coupon.DiscountType.PERCENTAGE);
        coupon.setDiscountValue(new BigDecimal("15"));
        coupon.setValidFrom(LocalDateTime.now().minusDays(1));
        coupon.setValidUntil(LocalDateTime.now().plusDays(30));
        coupon.setUsageLimit(5);
        coupon.setUsedCount(0);
        coupon.setActive(true);
        Coupon saved = couponRepository.saveAndFlush(coupon);

        // First 5 increments should succeed
        for (int i = 0; i < 5; i++) {
            int updated = couponRepository.incrementUsedCountIfWithinLimit(saved.getId());
            assertThat(updated).isEqualTo(1);
        }

        // 6th should fail
        int updated = couponRepository.incrementUsedCountIfWithinLimit(saved.getId());
        assertThat(updated).isZero();
    }

    @Test
    void saveAndFindDispute() {
        // Create an order first (dispute FK)
        Order order = new Order();
        order.setCustomerId(1L);
        order.setRestaurantId(2L);
        order.setStatus(Order.STATUS_DELIVERED);
        order.setTotalAmount(new BigDecimal("500"));
        Order savedOrder = orderRepository.saveAndFlush(order);

        Dispute dispute = new Dispute();
        dispute.setOrderId(savedOrder.getId());
        dispute.setType(Dispute.DisputeType.WRONG_ORDER);
        dispute.setStatus(Dispute.DisputeStatus.OPEN);
        dispute.setCustomerEvidence("Wrong items delivered");
        Dispute saved = disputeRepository.saveAndFlush(dispute);

        assertThat(saved.getId()).isNotNull();
        assertThat(disputeRepository.findByOrderId(savedOrder.getId())).isPresent();
        assertThat(disputeRepository.existsByOrderId(savedOrder.getId())).isTrue();
    }

    @Test
    void orderExtraColumnsExist() {
        // Verify the V5 ALTER TABLE added columns to orders
        Integer colCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() " +
                        "AND table_name = 'orders' AND column_name IN ('order_number','delivered_at','estimated_delivery_at')",
                Integer.class);
        assertThat(colCount).isEqualTo(3);

        // Verify writing and reading back the new columns
        Order order = new Order();
        order.setCustomerId(1L);
        order.setRestaurantId(2L);
        order.setStatus(Order.STATUS_DELIVERED);
        order.setTotalAmount(new BigDecimal("500"));
        order.setOrderNumber("ORD-001");
        order.setDeliveredAt(LocalDateTime.now());
        order.setEstimatedDeliveryAt(LocalDateTime.now().minusMinutes(15));
        Order saved = orderRepository.saveAndFlush(order);

        Order found = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getOrderNumber()).isEqualTo("ORD-001");
        assertThat(found.getDeliveredAt()).isNotNull();
        assertThat(found.getEstimatedDeliveryAt()).isNotNull();
    }
}