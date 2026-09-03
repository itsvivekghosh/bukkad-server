package com.bhukkad.payment;

import com.bhukkad.config.LoyaltyProperties;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.service.OrderPricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoyaltyRedemptionServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private OrderPricingService orderPricingService;

    private LoyaltyProperties loyaltyProperties;
    private LoyaltyRedemptionService service;

    private Order order;
    private Customer customer;

    @BeforeEach
    void setUp() {
        loyaltyProperties = new LoyaltyProperties();
        loyaltyProperties.setEnabled(true);
        loyaltyProperties.setPointsPerRupee(100);
        loyaltyProperties.setMaxRedemptionPercent(20);
        loyaltyProperties.setMinOrderAmount(100.0);
        service = new LoyaltyRedemptionService(loyaltyProperties, orderRepository, customerRepository, orderPricingService);

        customer = new Customer();
        customer.setId(5L);
        customer.setLoyaltyPoints(500);

        order = new Order();
        order.setId(9L);
        order.setCustomer(customer);
        order.setTotalAmount(1000.0);
    }

    @Test
    void redeemPoints_disabled_throwsBusiness() {
        loyaltyProperties.setEnabled(false);
        assertThrows(BusinessException.class, () -> service.redeemPoints(5L, 9L, 100));
    }

    @Test
    void redeemPoints_nonPositivePoints_throwsBusiness() {
        assertThrows(BusinessException.class, () -> service.redeemPoints(5L, 9L, 0));
        assertThrows(BusinessException.class, () -> service.redeemPoints(5L, 9L, -5));
    }

    @Test
    void redeemPoints_orderNotFound_throwsResourceNotFound() {
        when(orderRepository.findById(9L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.redeemPoints(5L, 9L, 100));
    }

    @Test
    void redeemPoints_notOwnersOrder_throwsUnauthorized() {
        Customer other = new Customer();
        other.setId(999L);
        order.setCustomer(other);
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order));

        assertThrows(UnauthorizedException.class, () -> service.redeemPoints(5L, 9L, 100));
    }

    @Test
    void redeemPoints_belowMinOrderAmount_throwsBusiness() {
        loyaltyProperties.setMinOrderAmount(5000.0);
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class, () -> service.redeemPoints(5L, 9L, 100));
    }

    @Test
    void redeemPoints_belowPointsPerRupee_throwsBusiness() {
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class, () -> service.redeemPoints(5L, 9L, 50));
    }

    @Test
    void redeemPoints_alreadyRedeemed_throwsBusiness() {
        order.setLoyaltyPointsRedeemed(10);
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class, () -> service.redeemPoints(5L, 9L, 100));
    }

    @Test
    void redeemPoints_zeroMaxDiscount_throwsBusiness() {
        loyaltyProperties.setMaxRedemptionPercent(0);
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class, () -> service.redeemPoints(5L, 9L, 100));
    }

    @Test
    void redeemPoints_insufficientBalance_throwsBusiness() {
        customer.setLoyaltyPoints(10);
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class, () -> service.redeemPoints(5L, 9L, 100));
    }

    @Test
    void redeemPoints_capsRedemptionAndDebitsBalance() {
        // orderTotal 1000, max 20% -> 200 -> 200*100 = 20000 points max
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order));
        when(orderPricingService.applyLoyaltyDiscount(9L, 5L, 100)).thenReturn(1.0);
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        LoyaltyRedemptionService.LoyaltyRedemptionResult result = service.redeemPoints(5L, 9L, 100);

        assertEquals(100, result.pointsRedeemed());
        assertEquals(1.0, result.discountAmount());
        assertEquals(1000.0, result.orderTotal());
        assertEquals(400, customer.getLoyaltyPoints());
        verify(customerRepository).save(customer);
    }

    @Test
    void redeemPoints_capsAtMaxDiscountPoints() {
        customer.setLoyaltyPoints(50000);
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order));
        when(orderPricingService.applyLoyaltyDiscount(9L, 5L, 20000)).thenReturn(200.0);
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        LoyaltyRedemptionService.LoyaltyRedemptionResult result = service.redeemPoints(5L, 9L, 50000);

        assertEquals(20000, result.pointsRedeemed());
        assertEquals(200.0, result.discountAmount());
        assertEquals(30000, customer.getLoyaltyPoints());
        verify(orderPricingService).applyLoyaltyDiscount(anyLong(), anyLong(), anyInt());
    }
}