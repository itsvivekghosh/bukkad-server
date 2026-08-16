package com.bhukkad.serviceImpl;

import com.bhukkad.config.CommissionTierProperties;
import com.bhukkad.config.CommissionTierProperties.Tier;
import com.bhukkad.dto.response.CommissionTierResponse;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CommissionTierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommissionTierServiceImplTest {

    @Mock
    private CommissionTierProperties commissionTierProperties;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private CommissionTierServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(commissionTierProperties.getDefaultRate()).thenReturn(15.0);
        CommissionTierProperties.Tier tier = new CommissionTierProperties.Tier();
        tier.setName("Standard");
        tier.setMinOrders(0);
        tier.setMaxOrders(100);
        tier.setCommissionPercent(15.0);
        tier.setDescription("Standard rate");
        lenient().when(commissionTierProperties.getTiers()).thenReturn(List.of(tier));
    }

    @Test
    void calculateCommission_restaurantNotFound_throws() {
        when(restaurantRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.calculateCommission(1L));
    }

    @Test
    void calculateCommission_returnsTierInfo() {
        Restaurant restaurant = new Restaurant();
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(1L);
        restaurant.setOwner(owner);
        restaurant.setCommissionPercent(15.0);

        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        when(orderRepository.countByRestaurantIdAndCreatedAtAfter(eq(1L), any(LocalDateTime.class))).thenReturn(50L);

        CommissionTierResponse response = service.calculateCommission(1L);

        assertNotNull(response);
        assertEquals("Standard", response.getTierName());
        assertEquals(15.0, response.getCommissionPercent());
        verify(orderRepository).countByRestaurantIdAndCreatedAtAfter(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void getCommissionTiers_returnsAllTiers() {
        List<CommissionTierResponse> tiers = service.getCommissionTiers();

        assertEquals(1, tiers.size());
        assertEquals("Standard", tiers.get(0).getTierName());
    }

    @Test
    void getEffectiveCommissionRate_customRate_returnsCustom() {
        Restaurant restaurant = new Restaurant();
        restaurant.setCommissionPercent(12.0);

        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));

        double rate = service.getEffectiveCommissionRate(1L);

        assertEquals(0.12, rate, 0.001);
    }

    @Test
    void getEffectiveCommissionRate_noCustom_usesTier() {
        Restaurant restaurant = new Restaurant();
        restaurant.setCommissionPercent(null);

        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        when(orderRepository.countByRestaurantIdAndCreatedAtAfter(eq(1L), any(LocalDateTime.class))).thenReturn(50L);

        double rate = service.getEffectiveCommissionRate(1L);

        assertEquals(0.15, rate, 0.001);
    }

    @Test
    void updateCommissionTiers_updatesAllRestaurants() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setCommissionPercent(null);

        when(restaurantRepository.findAll()).thenReturn(List.of(restaurant));
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));

        service.updateCommissionTiers();

        assertEquals(15.0, restaurant.getCommissionPercent());
        verify(restaurantRepository).save(restaurant);
    }
}