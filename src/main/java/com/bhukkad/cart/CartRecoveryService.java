package com.bhukkad.cart;

import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.entity.Cart;
import com.bhukkad.entity.Coupon;
import com.bhukkad.notification.push.PushNotificationSender;
import com.bhukkad.repository.CartItemRepository;
import com.bhukkad.repository.CartRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.serviceImpl.CouponServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Abandoned-cart recovery sweep.
 *
 * <p>Discovers carts that were last touched more than {@code idleMinutes} ago,
 * still carry items, and were never converted into an order since their last
 * update. Each such cart receives one platform-wide percentage coupon (minted
 * through {@link CouponServiceImpl#createRecoveryCoupon}) and a push
 * notification nudging the customer back.</p>
 *
 * <p>Processed carts are recorded in Redis under
 * {@code bhukkad:cart-recovery:<cartId>} with a TTL of {@code couponValidityDays}
 * so a cart is not nagged twice while its coupon is still valid. Every failure
 * path is swallowed with a warn log — a broken cart, coupon or channel must
 * never take the scheduler down.</p>
 *
 * <p>Note on {@link #findIdleCarts()}: the sweep deliberately stays inside one
 * {@link Transactional} read-write transaction. Carts are returned detached by
 * {@code CartRepository.findAll()} and their {@code customer} association is
 * lazy, so the "no order since" check needs an open persistence context; the
 * coupon write then joins the same transaction. A caught per-cart failure does
 * not cross the transaction boundary, so a single bad cart cannot poison the
 * rest of the sweep.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartRecoveryService {

    private static final String RECOVERY_KEY_PREFIX = "cart-recovery:";
    private static final String RECOVERY_CODE_PREFIX = "BACK";

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final CouponServiceImpl couponService;
    private final PushNotificationSender pushNotificationSender;
    private final RedisCacheService redisCacheService;
    private final CartRecoveryProperties properties;

    /**
     * Runs one recovery sweep: finds idle carts and processes each one.
     * Called by {@link CartRecoveryScheduler}; a no-op when {@code enabled} is
     * false.
     */
    @Transactional
    public void recoverAbandonedCarts() {
        if (!properties.isEnabled()) {
            log.debug("CART_RECOVERY_DISABLED");
            return;
        }
        for (Cart cart : findIdleCarts()) {
            recoverCart(cart);
        }
    }

    /**
     * Carts that look abandoned: last updated before {@code now - idleMinutes},
     * still holding at least one item, and with no order placed by the customer
     * after the cart's last activity.
     *
     * <p>Package-private and read via {@link #recoverAbandonedCarts()} so the
     * lazy {@code customer} association is always accessed inside the sweep's
     * transaction.</p>
     */
    List<Cart> findIdleCarts() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(properties.getIdleMinutes());
        return cartRepository.findAll().stream()
                .filter(cart -> cart.getUpdatedAt() != null)
                .filter(cart -> cart.getUpdatedAt().isBefore(cutoff))
                .filter(cart -> !cartItemRepository.findByCartId(cart.getId()).isEmpty())
                .filter(this::hasNoOrderSince)
                .toList();
    }

    private boolean hasNoOrderSince(Cart cart) {
        return orderRepository.countByCustomerIdAndCreatedAtAfter(
                cart.getCustomer().getId(), cart.getUpdatedAt()) == 0;
    }

    private void recoverCart(Cart cart) {
        Long cartId = cart.getId();
        try {
            if (alreadyProcessed(cartId)) {
                log.debug("CART_RECOVERY_SKIP | cartId={} | reason=already-processed", cartId);
                return;
            }

            Long customerId = cart.getCustomer().getId();
            String code = buildCouponCode(cartId);
            Coupon coupon = couponService.createRecoveryCoupon(
                    code,
                    buildDescription(code),
                    properties.getCouponPercent(),
                    properties.getCouponMinOrder(),
                    properties.getCouponValidityDays());

            // Mark before notifying: a failed fan-out must not mint a duplicate
            // coupon on the next sweep.
            markProcessed(cartId);
            notifyCustomer(customerId, coupon.getCode());
            log.info("CART_RECOVERY_PROCESSED | cartId={} | customerId={} | coupon={}",
                    cartId, customerId, coupon.getCode());
        } catch (Exception ex) {
            log.warn("CART_RECOVERY_FAILED | cartId={} | error={}", cartId, ex.getMessage());
        }
    }

    /**
     * Best-effort push. {@code NotificationService} has no abandoned-cart
     * method, so the customer-facing alert goes through the push sender
     * directly; any failure is logged and swallowed.
     */
    private void notifyCustomer(Long customerId, String couponCode) {
        String title = "Your cart is waiting";
        String body = "We saved your cart \u2014 use " + couponCode + " for "
                + formatPercent(properties.getCouponPercent())
                + "% off orders above \u20B9" + (int) properties.getCouponMinOrder() + ".";
        try {
            pushNotificationSender.sendToUser(customerId, title, body);
        } catch (Exception ex) {
            log.warn("CART_RECOVERY_NOTIFY_FAILED | customerId={} | error={}",
                    customerId, ex.getMessage());
        }
    }

    private boolean alreadyProcessed(Long cartId) {
        return redisCacheService.exists(RECOVERY_KEY_PREFIX + cartId);
    }

    private void markProcessed(Long cartId) {
        long ttlSeconds = properties.getCouponValidityDays() * 24L * 3600L;
        redisCacheService.set(RECOVERY_KEY_PREFIX + cartId, Boolean.TRUE, ttlSeconds);
    }

    private String buildCouponCode(Long cartId) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6)
                .toUpperCase(Locale.ROOT);
        return RECOVERY_CODE_PREFIX + "-" + cartId + "-" + suffix;
    }

    private String buildDescription(String code) {
        return "We saved your cart! Use " + code + " for "
                + formatPercent(properties.getCouponPercent())
                + "% off orders above \u20B9" + (int) properties.getCouponMinOrder() + ".";
    }

    private static String formatPercent(double percent) {
        return percent == Math.floor(percent) ? String.valueOf((int) percent) : String.valueOf(percent);
    }
}
