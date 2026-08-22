package com.bhukkad.service;

import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.Review;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaterializedViewRefreshServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query nativeQuery;

    @InjectMocks
    private MaterializedViewRefreshService service;

    @BeforeEach
    void setUp() {
        lenient().when(entityManager.createNativeQuery(any(String.class))).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.executeUpdate()).thenReturn(1);
    }

    @Test
    void refreshRestaurantRatings_populatesSummary() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);

        when(restaurantRepository.findAll()).thenReturn(List.of(restaurant));
        when(restaurantRepository.count()).thenReturn(1L);
        when(reviewRepository.getAverageRatingByRestaurant(1L)).thenReturn(4.5);
        when(reviewRepository.countByRestaurant(1L)).thenReturn(10L);
        when(reviewRepository.countByRestaurantAndStatus(1L, Review.ModerationStatus.APPROVED)).thenReturn(8L);

        service.refreshRestaurantRatings();

        verify(entityManager, atLeast(1)).createNativeQuery(any(String.class));
        verify(nativeQuery, atLeast(1)).setParameter(eq(1), eq(1L));
        verify(nativeQuery, atLeast(1)).setParameter(eq(2), eq(4.5));
        verify(nativeQuery, atLeast(1)).setParameter(eq(3), eq(10L));
        verify(nativeQuery, atLeast(1)).setParameter(eq(4), eq(8L));
        verify(nativeQuery, atLeast(1)).executeUpdate();
    }

    @Test
    void refreshRestaurantOrderStats_populatesSummary() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(2L);

        when(restaurantRepository.findAll()).thenReturn(List.of(restaurant));
        when(restaurantRepository.count()).thenReturn(1L);
        when(orderRepository.countByRestaurantId(2L)).thenReturn(50L);
        when(orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(eq(2L), eq(Order.OrderStatus.DELIVERED), any()))
                .thenReturn(40L);
        when(orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(eq(2L), eq(Order.OrderStatus.CANCELLED), any()))
                .thenReturn(5L);
        when(orderRepository.sumRestaurantRevenueSince(eq(2L), any())).thenReturn(5000.0);

        service.refreshRestaurantOrderStats();

        verify(entityManager, atLeast(1)).createNativeQuery(any(String.class));
        verify(nativeQuery, atLeast(1)).setParameter(eq(1), eq(2L));
        verify(nativeQuery, atLeast(1)).setParameter(eq(5), eq(5000.0));
        verify(nativeQuery, atLeast(1)).setParameter(eq(6), eq(100.0)); // avg = 5000/50
        verify(nativeQuery, atLeast(1)).executeUpdate();
    }

    @Test
    void refreshAll_callsBothRefreshes() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);

        when(restaurantRepository.findAll()).thenReturn(List.of(restaurant));
        when(restaurantRepository.count()).thenReturn(1L);
        when(reviewRepository.getAverageRatingByRestaurant(1L)).thenReturn(3.0);
        when(reviewRepository.countByRestaurant(1L)).thenReturn(5L);
        when(reviewRepository.countByRestaurantAndStatus(1L, Review.ModerationStatus.APPROVED)).thenReturn(5L);
        when(orderRepository.countByRestaurantId(1L)).thenReturn(20L);
        when(orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(any(), any(), any())).thenReturn(0L);
        when(orderRepository.sumRestaurantRevenueSince(any(), any())).thenReturn(2000.0);

        service.refreshAll();

        verify(entityManager, atLeast(2)).createNativeQuery(any(String.class));
        verify(nativeQuery, atLeast(2)).executeUpdate();
    }
}
