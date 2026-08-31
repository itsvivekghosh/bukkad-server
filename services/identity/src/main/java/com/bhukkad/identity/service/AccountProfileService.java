package com.bhukkad.identity.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.identity.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Account-profile depth (Priority 1): the JOINED user hierarchy (customer /
 * owner / agent / admin), device tokens and favourites.
 */
@Service
@RequiredArgsConstructor
public class AccountProfileService {

    private final UserRepository userRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final FavoriteRestaurantRepository favoriteRepository;

    @Transactional
    public User registerOwner(String email, String fullName, String phoneNumber, String businessLicense) {
        if (userRepository.findAll().stream().anyMatch(u ->
                (u instanceof RestaurantOwner ro) && email.equalsIgnoreCase(ro.getEmail()))) {
            throw new BusinessException("Owner email already registered");
        }
        RestaurantOwner owner = new RestaurantOwner();
        owner.setRole(User.UserRole.RESTAURANT_OWNER);
        owner.setEmail(email);
        owner.setFullName(fullName);
        owner.setPhoneNumber(phoneNumber);
        owner.setBusinessLicense(businessLicense);
        owner.setVerified(false);
        return userRepository.save(owner);
    }

    @Transactional
    public User registerAgent(String email, String fullName, String phoneNumber) {
        DeliveryAgent agent = new DeliveryAgent();
        agent.setRole(User.UserRole.DELIVERY_AGENT);
        agent.setEmail(email);
        agent.setFullName(fullName);
        agent.setPhoneNumber(phoneNumber);
        agent.setAvailable(false);
        agent.setVerified(false);
        return userRepository.save(agent);
    }

    @Transactional(readOnly = true)
    public User get(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new com.bhukkad.common.error.ResourceNotFoundException("User not found: " + userId));
    }

    @Transactional
    public DeviceToken registerDevice(Long userId, String token, String deviceType) {
        DeviceToken deviceToken = new DeviceToken();
        deviceToken.setUserId(userId);
        deviceToken.setToken(token);
        deviceToken.setDeviceType(deviceType);
        return deviceTokenRepository.save(deviceToken);
    }

    @Transactional(readOnly = true)
    public List<DeviceToken> devices(Long userId) {
        return deviceTokenRepository.findByUserId(userId);
    }

    @Transactional
    public FavoriteRestaurant addFavorite(Long customerId, Long restaurantId) {
        FavoriteRestaurant favorite = new FavoriteRestaurant();
        favorite.setCustomerId(customerId);
        favorite.setRestaurantId(restaurantId);
        return favoriteRepository.save(favorite);
    }

    @Transactional
    public void removeFavorite(Long customerId, Long restaurantId) {
        favoriteRepository.deleteByCustomerIdAndRestaurantId(customerId, restaurantId);
    }

    @Transactional(readOnly = true)
    public List<FavoriteRestaurant> favorites(Long customerId) {
        return favoriteRepository.findByCustomerId(customerId);
    }
}