package com.bhukkad.serviceImpl;

import com.bhukkad.entity.CartItem;
import com.bhukkad.entity.Coupon;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.MenuCategory;
import com.bhukkad.entity.MenuItem;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
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
        lenient().when(promotionEngineService.evaluateBestDiscount(any(), any(), anyDouble()))
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

        var result = orderPricingService.calculate(restaurant, List.of(item), "SAVE10", customer(), null, "UPI", null, null);

        assertEquals(200.0, result.subtotal());
        assertEquals(40.0, result.deliveryFee());
        assertEquals(10.0, result.taxAmount());
        assertEquals(20.0, result.discountAmount());
        assertEquals(230.0, result.totalAmount());
        assertEquals(coupon, result.appliedCoupon());
    }

    @Test
    void calculate_freeDeliveryWhenThresholdMet() {
        Restaurant restaurant = restaurant(10L, 40.0, 150.0);
        restaurant.setFreeDeliveryAvailable(true);
        restaurant.setFreeDeliveryAbove(200.0);

        CartItem item = cartItem("Burger", 120.0, 2);

        var result = orderPricingService.calculate(restaurant, List.of(item), null, customer(), null, "UPI", null, null);

        assertEquals(0.0, result.deliveryFee());
        assertEquals(252.0, result.totalAmount());
    }

    @Test
    void calculate_emptyCart_throws() {
        Restaurant restaurant = restaurant(10L, 40.0, null);

        assertThrows(BusinessException.class,
                () -> orderPricingService.calculate(restaurant, List.of(), null, customer(), null, "UPI", null, null));
    }

    @Test
    void validateCartItems_unavailableItem_throws() {
        Restaurant restaurant = restaurant(10L, 40.0, null);
        CartItem item = cartItem("Burger", 100.0, 1);
        item.getMenuItem().setAvailable(false);

        assertThrows(BusinessException.class,
                () -> orderPricingService.validateCartItems(restaurant, List.of(item)));
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
