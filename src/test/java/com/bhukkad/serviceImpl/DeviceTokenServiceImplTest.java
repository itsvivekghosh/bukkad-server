package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.DeviceTokenRequest;
import com.bhukkad.dto.response.DeviceTokenResponse;
import com.bhukkad.entity.DeviceToken;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.repository.DeviceTokenRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceTokenServiceImplTest {

    @Mock
    private DeviceTokenRepository deviceTokenRepository;
    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private DeviceTokenServiceImpl service;

    private User user;
    private DeviceTokenRequest request;
    private DeviceToken existingToken;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(7L);

        request = new DeviceTokenRequest();
        request.setToken("device-token-abc");
        request.setPlatform("ANDROID");

        existingToken = new DeviceToken();
        existingToken.setId(1L);
        existingToken.setToken("device-token-abc");
        existingToken.setUser(user);
        existingToken.setPlatform(DeviceToken.Platform.ANDROID);
        existingToken.setActive(true);
    }

    @Test
    void registerToken_createsNewToken_whenNotFound() {
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(deviceTokenRepository.findByToken("device-token-abc")).thenReturn(Optional.empty());
        when(deviceTokenRepository.save(any(DeviceToken.class))).thenAnswer(inv -> {
            DeviceToken t = inv.getArgument(0);
            t.setId(2L);
            return t;
        });

        DeviceTokenResponse result = service.registerToken(request);

        assertEquals(2L, result.getId());
        assertEquals("ANDROID", result.getPlatform());
        assertTrue(result.getActive());
    }

    @Test
    void registerToken_updatesExistingToken() {
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(deviceTokenRepository.findByToken("device-token-abc")).thenReturn(Optional.of(existingToken));
        when(deviceTokenRepository.save(any(DeviceToken.class))).thenReturn(existingToken);

        DeviceTokenResponse result = service.registerToken(request);

        assertEquals(1L, result.getId());
        assertEquals(DeviceToken.Platform.ANDROID, existingToken.getPlatform());
        assertTrue(existingToken.getActive());
    }

    @Test
    void registerToken_throws_whenPlatformInvalid() {
        request.setPlatform("UNKNOWN");
        when(securityUtils.getCurrentUser()).thenReturn(user);

        assertThrows(BusinessException.class, () -> service.registerToken(request));
    }

    @Test
    void unregisterToken_deactivatesOwnToken() {
        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        when(deviceTokenRepository.findByToken("device-token-abc")).thenReturn(Optional.of(existingToken));
        when(deviceTokenRepository.save(any(DeviceToken.class))).thenReturn(existingToken);

        service.unregisterToken("device-token-abc");

        assertTrue(!existingToken.getActive());
        verify(deviceTokenRepository).save(existingToken);
    }

    @Test
    void unregisterToken_throws_whenOtherUsersToken() {
        User other = new User();
        other.setId(99L);
        existingToken.setUser(other);

        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        when(deviceTokenRepository.findByToken("device-token-abc")).thenReturn(Optional.of(existingToken));

        assertThrows(BusinessException.class, () -> service.unregisterToken("device-token-abc"));
    }

    @Test
    void unregisterToken_noop_whenTokenNotFound() {
        when(deviceTokenRepository.findByToken("missing")).thenReturn(Optional.empty());

        service.unregisterToken("missing");
        verify(deviceTokenRepository, never()).save(any(DeviceToken.class));
    }
}