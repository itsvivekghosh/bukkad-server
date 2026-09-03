package com.bhukkad.serviceImpl;

import com.bhukkad.dto.response.FavoriteRestaurantResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.FavoriteRestaurant;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.FavoriteRestaurantRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FavoriteServiceImplTest {

    @Mock
    private FavoriteRestaurantRepository favoriteRestaurantRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private FavoriteServiceImpl service;

    private static final Long CUSTOMER_ID = 1L;
    private static final Long RESTAURANT_ID = 10L;
    private Customer customer;
    private Restaurant restaurant;
    private FavoriteRestaurant favorite;

    @BeforeEach
    void setUp() {
        customer = new Customer();
        customer.setId(CUSTOMER_ID);

        restaurant = new Restaurant();
        restaurant.setId(RESTAURANT_ID);
        restaurant.setName("Test Restaurant");
        restaurant.setImageUrl("/img/test.png");
        restaurant.setAverageRating(4.5);
        restaurant.setIsOpen(true);

        favorite = new FavoriteRestaurant();
        favorite.setCustomer(customer);
        favorite.setRestaurant(restaurant);
    }

    @Test
    void listFavorites_returnsResponses() {
        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(favoriteRestaurantRepository.findByCustomerIdOrderByCreatedAtDesc(CUSTOMER_ID))
                .thenReturn(List.of(favorite));

        List<FavoriteRestaurantResponse> result = service.listFavorites();

        assertEquals(1, result.size());
        assertEquals(RESTAURANT_ID, result.get(0).getRestaurantId());
    }

    @Test
    void listFavorites_returnsEmpty_whenNoFavorites() {
        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(favoriteRestaurantRepository.findByCustomerIdOrderByCreatedAtDesc(CUSTOMER_ID))
                .thenReturn(List.of());

        List<FavoriteRestaurantResponse> result = service.listFavorites();
        assertEquals(0, result.size());
    }

    @Test
    void addFavorite_savesAndReturns() {
        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(restaurantRepository.findById(RESTAURANT_ID)).thenReturn(Optional.of(restaurant));
        when(favoriteRestaurantRepository.existsByCustomerIdAndRestaurantId(CUSTOMER_ID, RESTAURANT_ID))
                .thenReturn(false);
        when(favoriteRestaurantRepository.save(any(FavoriteRestaurant.class))).thenReturn(favorite);

        FavoriteRestaurantResponse result = service.addFavorite(RESTAURANT_ID);

        assertNotNull(result);
        assertEquals(RESTAURANT_ID, result.getRestaurantId());
    }

    @Test
    void addFavorite_throws_whenAlreadyExists() {
        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(restaurantRepository.findById(RESTAURANT_ID)).thenReturn(Optional.of(restaurant));
        when(favoriteRestaurantRepository.existsByCustomerIdAndRestaurantId(CUSTOMER_ID, RESTAURANT_ID))
                .thenReturn(true);

        assertThrows(BusinessException.class, () -> service.addFavorite(RESTAURANT_ID));
    }

    @Test
    void addFavorite_throws_whenCustomerNotFound() {
        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.addFavorite(RESTAURANT_ID));
    }

    @Test
    void removeFavorite_deletes_whenExists() {
        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(favoriteRestaurantRepository.findByCustomerIdAndRestaurantId(CUSTOMER_ID, RESTAURANT_ID))
                .thenReturn(Optional.of(favorite));

        service.removeFavorite(RESTAURANT_ID);
        verify(favoriteRestaurantRepository).delete(favorite);
    }

    @Test
    void removeFavorite_throws_whenNotFound() {
        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(favoriteRestaurantRepository.findByCustomerIdAndRestaurantId(CUSTOMER_ID, RESTAURANT_ID))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.removeFavorite(RESTAURANT_ID));
    }
}