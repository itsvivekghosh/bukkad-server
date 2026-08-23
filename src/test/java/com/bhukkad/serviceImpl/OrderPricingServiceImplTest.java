package com.bhukkad.serviceImpl;

import com.bhukkad.entity.CartItem;
import com.bhukkad.entity.Coupon;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.MenuCategory;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.dto.response.MembershipStatusResponse;
import com.bhukkad.membership.MembershipService;
import com.bhukkad.promotion.PromotionEngineService;
import com.bhukkad.service.CouponService;
import com.bhukkad.zone.DeliveryZoneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPricingServiceImplTest {

    @Mock
    private CouponService couponService;

    @Mock
    private DeliveryZoneService deliveryZoneService;

    @Mock
    private MembershipService membershipService;

    @Mock
    private PromotionEngineService promotionEngineService;

    @InjectMocks
    private OrderPricingServiceImpl orderPricingService;

    @BeforeEach
    void setUpMembershipAndCampaigns() {
        lenient().when(membershipService.getActiveMembership(anyLong()))
                .thenReturn(MembershipStatusResponse.builder().active(false).build());
        lenient().when(promotionEngineService.evaluateBestDiscount(any(), any(), anyDouble(), anyList()))
                .thenReturn(PromotionEngineService.PromotionDiscountResult.none());
    }

    @Test
    void calculate_appliesTaxDeliveryAndCoupon() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 2);
        Coupon coupon = new Coupon();
        coupon.setCode("SAVE10");

        when(couponService.validateCoupon(eq("SAVE10"), eq(200.0), eq(10L), eq(1L))).thenReturn(coupon);
        when(couponService.calculateDiscount(coupon, 200.0)).thenReturn(20.0);

        var result = orderPricingService.calculate(restaurant, List.of(item), "SAVE10", customer(), null, "UPI", null, null, null, null);

        assertEquals(200.0, result.subtotal());
        assertEquals(40.0, result.deliveryFee());
        // tax is 5% of subtotal: 200 * 0.05
        assertEquals(10.0, result.taxAmount());
        assertEquals(20.0, result.discountAmount());
        // 200 + 40 + 10 - 20 = 230
        assertEquals(230.0, result.totalAmount());
        assertEquals(coupon, result.appliedCoupon());
    }

    @Test
    void calculate_freeDeliveryWhenThresholdMet() {
        Restaurant restaurant = restaurant(10L, 40.0, 150.0);
        restaurant.setFreeDeliveryAvailable(true);
        restaurant.setFreeDeliveryAbove(200.0);

        CartItem item = cartItem("Burger", 120.0, 2);

        var result = orderPricingService.calculate(restaurant, List.of(item), null, customer(), null, "UPI", null, null, null, null);

        assertEquals(0.0, result.deliveryFee());
        // subtotal 240 + tax 5% (12) = 252
        assertEquals(252.0, result.totalAmount());
    }

    @Test
    void calculate_emptyCart_throws() {
        Restaurant restaurant = restaurant(10L, 40.0, null);

        assertThrows(BusinessException.class,
                () -> orderPricingService.calculate(restaurant, List.of(), null, customer(), null, "UPI", null, null, null, null));
    }

    @Test
    void validateCartItems_unavailableItem_throws() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);
        item.getMenuItem().setAvailable(false);

        assertThrows(BusinessException.class,
                () -> orderPricingService.validateCartItems(restaurant, List.of(item)));
    }

    @Test
    void validateCartItems_differentRestaurant_throws() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);
        item.getMenuItem().getCategory().getRestaurant().setId(99L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderPricingService.validateCartItems(restaurant, List.of(item)));
        assertEquals("Cart contains items from a different restaurant", ex.getMessage());
    }

    @Test
    void validateCartItems_insufficientStock_throws() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 5);
        item.getMenuItem().setStockQuantity(2);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderPricingService.validateCartItems(restaurant, List.of(item)));
        assertEquals("Insufficient stock for: Burger", ex.getMessage());
    }

    @Test
    void validateCartItems_valid_doesNotThrow() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);
        item.getMenuItem().setStockQuantity(10);

        orderPricingService.validateCartItems(restaurant, List.of(item));
    }

    @Test
    void calculate_belowMinimumOrder_throws() {
        Restaurant restaurant = restaurant(10L, 40.0, 500.0);
        CartItem item = cartItem("Burger", 100.0, 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderPricingService.calculate(restaurant, List.of(item), null, customer(), null, "UPI", null, null, null, null));
        assertEquals("Minimum order amount is ₹500.0", ex.getMessage());
    }

    @Test
    void calculate_membershipFreeDelivery_appliesZeroFee() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);
        when(membershipService.getActiveMembership(1L))
                .thenReturn(MembershipStatusResponse.builder().active(true).freeDelivery(true).build());

        var result = orderPricingService.calculate(restaurant, List.of(item), null, customer(), null, "UPI", null, null, null, null);

        assertEquals(0.0, result.deliveryFee());
        // subtotal 100 + delivery 0 (membership) + tax 5% = 105
        assertEquals(105.0, result.totalAmount());
    }

    @Test
    void calculate_campaignFreeDeliveryWithCoordinates_appliesZeroFee() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        com.bhukkad.entity.Address address = new com.bhukkad.entity.Address();
        address.setLatitude(12.91);
        address.setLongitude(77.50);
        restaurant.setAddress(address);
        CartItem item = cartItem("Burger", 100.0, 1);
        when(promotionEngineService.evaluateBestDiscount(any(), any(), anyDouble()))
                .thenReturn(new PromotionEngineService.PromotionDiscountResult(null, 0.0, true));

        var result = orderPricingService.calculate(restaurant, List.of(item), null, customer(), null, "UPI", null, null,
                12.97, 77.59);

        assertEquals(0.0, result.deliveryFee());
    }

    @Test
    void calculate_zoneDeliveryFee_withCoordinates() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        com.bhukkad.entity.Address address = new com.bhukkad.entity.Address();
        address.setLatitude(12.91);
        address.setLongitude(77.50);
        restaurant.setAddress(address);
        CartItem item = cartItem("Burger", 100.0, 1);
        when(deliveryZoneService.calculateDeliveryFee(eq(restaurant), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(25.0);
        // resolveDeliveryFee consults the subtotal-only promotion overload before
        // falling through to zone-based pricing; keep the lenient default in place.
        lenient().when(promotionEngineService.evaluateBestDiscount(any(), any(), anyDouble()))
                .thenReturn(PromotionEngineService.PromotionDiscountResult.none());

        var result = orderPricingService.calculate(restaurant, List.of(item), null, customer(), null, "UPI", null, null,
                12.97, 77.59);

        assertEquals(25.0, result.deliveryFee());
    }

    @Test
    void calculate_defaultDeliveryFee_whenNoCoordinates() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);

        var result = orderPricingService.calculate(restaurant, List.of(item), null, customer(), null, "UPI", null, null, null, null);

        assertEquals(40.0, result.deliveryFee());
    }

    @Test
    void calculate_nullDeliveryFee_usesDefaultConstant() {
        Restaurant restaurant = restaurant(10L, null, null);
        CartItem item = cartItem("Burger", 100.0, 1);

        var result = orderPricingService.calculate(restaurant, List.of(item), null, customer(), null, "UPI", null, null, null, null);

        assertEquals(com.bhukkad.util.Constants.DEFAULT_DELIVERY_FEE, result.deliveryFee());
    }

    @Test
    void calculate_membershipDiscount_applied() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);
        when(membershipService.applyMembershipDiscount(1L, 100.0)).thenReturn(5.0);

        var result = orderPricingService.calculate(restaurant, List.of(item), null, customer(), null, "UPI", null, null, null, null);

        assertEquals(5.0, result.discountAmount());
    }

    @Test
    void calculate_campaignDiscount_applied() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);
        when(promotionEngineService.evaluateBestDiscount(any(), any(), anyDouble(), anyList()))
                .thenReturn(new PromotionEngineService.PromotionDiscountResult(null, 10.0, false));

        var result = orderPricingService.calculate(restaurant, List.of(item), null, customer(), null, "UPI", null, null, null, null);

        assertEquals(10.0, result.discountAmount());
    }

    @Test
    void calculate_insufficientLoyaltyPoints_throws() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);
        Customer customer = customer();
        customer.setLoyaltyPoints(10);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderPricingService.calculate(restaurant, List.of(item), null, customer, 50, "UPI", null, null, null, null));
        assertEquals("Insufficient loyalty points", ex.getMessage());
    }

    @Test
    void calculate_loyaltyPointsRedeemed() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);
        Customer customer = customer();

        var result = orderPricingService.calculate(restaurant, List.of(item), null, customer, 50, "UPI", null, null, null, null);

        assertEquals(50, result.loyaltyPointsRedeemed());
        assertTrue(result.loyaltyDiscountAmount() > 0);
    }

    @Test
    void calculate_negativeLoyaltyPoints_clampedToZero() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);

        var result = orderPricingService.calculate(restaurant, List.of(item), null, customer(), -5, "UPI", null, null, null, null);

        assertEquals(0, result.loyaltyPointsRedeemed());
    }

    @Test
    void calculate_walletPayment_usesWalletBalance() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);
        Customer customer = customer();
        customer.setWalletBalance(500.0);

        var result = orderPricingService.calculate(restaurant, List.of(item), null, customer, null,
                Payment.PaymentMethod.WALLET.name(), null, null, null, null);

        // order total 145 (100 + tax 45? no: 100 + 40 delivery + 5 tax) covered by wallet
        assertEquals(145.0, result.walletAmountUsed());
        assertEquals(0.0, result.totalAmount());
    }

    @Test
    void calculate_walletPayment_insufficientBalance_throws() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);
        Customer customer = customer();
        customer.setWalletBalance(10.0);

        assertThrows(BusinessException.class,
                () -> orderPricingService.calculate(restaurant, List.of(item), null, customer, null,
                        Payment.PaymentMethod.WALLET.name(), null, null, null, null));
    }

    @Test
    void calculate_walletAmountToUse_applied() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);

        var result = orderPricingService.calculate(restaurant, List.of(item), null, customer(), null, "UPI",
                50.0, null, null, null);

        assertEquals(50.0, result.walletAmountUsed());
        // 145 total - 50 wallet = 95
        assertEquals(95.0, result.totalAmount());
    }

    @Test
    void calculate_useWallet_true_usesMinOfBalanceAndTotal() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);
        Customer customer = customer();
        customer.setWalletBalance(80.0);

        var result = orderPricingService.calculate(restaurant, List.of(item), null, customer, null, "UPI",
                null, true, null, null);

        assertEquals(80.0, result.walletAmountUsed());
    }

    @Test
    void calculate_walletAmountExceedsBalance_throws() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);
        Customer customer = customer();
        customer.setWalletBalance(10.0);

        assertThrows(BusinessException.class,
                () -> orderPricingService.calculate(restaurant, List.of(item), null, customer, null, "UPI",
                        50.0, null, null, null));
    }

    @Test
    void calculate_walletAmountExceedsTotal_throws() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);

        assertThrows(BusinessException.class,
                () -> orderPricingService.calculate(restaurant, List.of(item), null, customer(), null, "UPI",
                        5000.0, null, null, null));
    }

    @Test
    void calculate_nullCart_throws() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        assertThrows(BusinessException.class,
                () -> orderPricingService.calculate(restaurant, null, null, customer(), null, "UPI", null, null, null, null));
    }

    private Customer customer() {
        Customer customer = new Customer();
        customer.setId(1L);
        customer.setLoyaltyPoints(100);
        customer.setWalletBalance(1000.0);
        return customer;
    }

    private Restaurant restaurant(Long id, Double deliveryFee, Double minimumOrder) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(id);
        restaurant.setDeliveryFee(deliveryFee);
        restaurant.setMinimumOrderAmount(minimumOrder);
        restaurant.setFreeDeliveryAvailable(false);
        return restaurant;
    }

    private CartItem cartItem(String name, double price, int quantity) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(10L);

        MenuCategory category = new MenuCategory();
        category.setRestaurant(restaurant);

        MenuItem menuItem = new MenuItem();
        menuItem.setName(name);
        menuItem.setPrice(price);
        menuItem.setAvailable(true);
        menuItem.setCategory(category);

        CartItem cartItem = new CartItem();
        cartItem.setMenuItem(menuItem);
        cartItem.setQuantity(quantity);
        return cartItem;
    }
}
