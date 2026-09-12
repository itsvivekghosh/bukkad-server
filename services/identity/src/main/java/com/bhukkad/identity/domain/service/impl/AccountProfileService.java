package com.bhukkad.identity.domain.service.impl;

import com.bhukkad.identity.domain.entity.DeliveryAgent;
import com.bhukkad.identity.domain.entity.DeviceToken;
import com.bhukkad.identity.domain.entity.FavoriteRestaurant;
import com.bhukkad.identity.domain.entity.RestaurantOwner;
import com.bhukkad.identity.domain.entity.User;
import com.bhukkad.identity.domain.repository.DeviceTokenRepository;
import com.bhukkad.identity.domain.repository.FavoriteRestaurantRepository;
import com.bhukkad.identity.domain.repository.RestaurantOwnerRepository;
import com.bhukkad.identity.domain.repository.UserRepository;

import com.bhukkad.common.error.BusinessException;
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
    private final RestaurantOwnerRepository restaurantOwnerRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final FavoriteRestaurantRepository favoriteRepository;

    @Transactional
    public User registerOwner(String email, String fullName, String phoneNumber, String businessLicense) {
        // PERF-3: keyed EXISTS on the owners table (SQL predicate) replaces the
        // findAll()-over-every-user anyMatch scan that grew with the customer
        // base. Case-insensitive comparison preserved (equalsIgnoreCase).
        if (restaurantOwnerRepository.existsByEmailIgnoreCase(email)) {
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
        // Upsert by token: devices migrate between users (re-login, factory
        // reset), so a token owned by another account is re-pointed instead of
        // colliding with the unique constraint.
        DeviceToken deviceToken = deviceTokenRepository.findByToken(token)
                .orElseGet(DeviceToken::new);
        deviceToken.setUserId(userId);
        deviceToken.setToken(token);
        deviceToken.setDeviceType(deviceType);
        if (deviceToken.getPlatform() == null) {
            deviceToken.setPlatform(DeviceToken.Platform.WEB);
        }
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