package com.bhukkad.serviceImpl;

import com.bhukkad.dto.response.FavoriteRestaurantResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.FavoriteRestaurant;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.FavoriteRestaurantRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FavoriteServiceImpl implements FavoriteService {

    private final FavoriteRestaurantRepository favoriteRestaurantRepository;
    private final CustomerRepository customerRepository;
    private final RestaurantRepository restaurantRepository;
    private final SecurityUtils securityUtils;

    @Override
    public List<FavoriteRestaurantResponse> listFavorites() {
        Long customerId = securityUtils.getCurrentUserId();
        return favoriteRestaurantRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public FavoriteRestaurantResponse addFavorite(Long restaurantId) {
        Long customerId = securityUtils.getCurrentUserId();
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        if (favoriteRestaurantRepository.existsByCustomerIdAndRestaurantId(customerId, restaurantId)) {
            throw new BusinessException("Restaurant already in favorites");
        }

        FavoriteRestaurant favorite = new FavoriteRestaurant();
        favorite.setCustomer(customer);
        favorite.setRestaurant(restaurant);
        favorite = favoriteRestaurantRepository.save(favorite);
        return toResponse(favorite);
    }

    @Override
    @Transactional
    public void removeFavorite(Long restaurantId) {
        Long customerId = securityUtils.getCurrentUserId();
        FavoriteRestaurant favorite = favoriteRestaurantRepository
                .findByCustomerIdAndRestaurantId(customerId, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Favorite not found"));
        favoriteRestaurantRepository.delete(favorite);
    }

    private FavoriteRestaurantResponse toResponse(FavoriteRestaurant favorite) {
        Restaurant restaurant = favorite.getRestaurant();
        return FavoriteRestaurantResponse.builder()
                .restaurantId(restaurant.getId())
                .restaurantName(restaurant.getName())
                .imageUrl(restaurant.getImageUrl())
                .averageRating(restaurant.getAverageRating())
                .isOpen(restaurant.getIsOpen())
                .build();
    }
}
