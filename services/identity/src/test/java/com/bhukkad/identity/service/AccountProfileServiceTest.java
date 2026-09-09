package com.bhukkad.identity.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.identity.domain.User;
import com.bhukkad.identity.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountProfileServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private com.bhukkad.identity.domain.DeviceTokenRepository deviceTokenRepository;
    @Mock private com.bhukkad.identity.domain.FavoriteRestaurantRepository favoriteRepository;
    @InjectMocks private AccountProfileService service;

    @Test
    void registerOwner_savesWithOwnerRole() {
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User owner = service.registerOwner("o@b.com", "Owner", "999", "LIC");

        assertThat(owner.getRole()).isEqualTo(User.UserRole.RESTAURANT_OWNER);
        verify(userRepository).save(any(User.class));
    }

    @Test
    void registerOwner_duplicateEmail_throws() {
        com.bhukkad.identity.domain.RestaurantOwner existing = new com.bhukkad.identity.domain.RestaurantOwner();
        existing.setEmail("o@b.com");
        when(userRepository.findAll()).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.registerOwner("o@b.com", "Owner", "999", "LIC"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void registerAgent_setsDefaults() {
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        com.bhukkad.identity.domain.DeliveryAgent agent =
                (com.bhukkad.identity.domain.DeliveryAgent) service.registerAgent("a@b.com", "Agent", "888");

        assertThat(agent.getRole()).isEqualTo(User.UserRole.DELIVERY_AGENT);
        assertThat(agent.getAvailable()).isFalse();
        assertThat(agent.getVerified()).isFalse();
    }

    @Test
    void get_unknownUser_throws() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(com.bhukkad.common.error.ResourceNotFoundException.class);
    }

    @Test
    void addFavorite_saves() {
        when(favoriteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var fav = service.addFavorite(1L, 10L);
        assertThat(fav.getRestaurantId()).isEqualTo(10L);
    }

    @Test
    void removeFavorite_deletes() {
        service.removeFavorite(1L, 10L);
        verify(favoriteRepository).deleteByCustomerIdAndRestaurantId(1L, 10L);
    }
}
