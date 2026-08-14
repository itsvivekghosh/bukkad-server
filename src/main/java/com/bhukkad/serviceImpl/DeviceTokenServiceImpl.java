package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.DeviceTokenRequest;
import com.bhukkad.dto.response.DeviceTokenResponse;
import com.bhukkad.entity.DeviceToken;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.repository.DeviceTokenRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.DeviceTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceTokenServiceImpl implements DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final SecurityUtils securityUtils;

    @Override
    @Transactional
    public DeviceTokenResponse registerToken(DeviceTokenRequest request) {
        User user = securityUtils.getCurrentUser();
        DeviceToken.Platform platform = parsePlatform(request.getPlatform());

        DeviceToken token = deviceTokenRepository.findByToken(request.getToken())
                .orElseGet(DeviceToken::new);
        token.setUser(user);
        token.setToken(request.getToken());
        token.setPlatform(platform);
        token.setActive(true);
        token = deviceTokenRepository.save(token);

        return DeviceTokenResponse.builder()
                .id(token.getId())
                .platform(token.getPlatform().name())
                .active(token.getActive())
                .build();
    }

    @Override
    @Transactional
    public void unregisterToken(String tokenValue) {
        deviceTokenRepository.findByToken(tokenValue).ifPresent(token -> {
            if (!token.getUser().getId().equals(securityUtils.getCurrentUserId())) {
                throw new BusinessException("Cannot unregister another user's device token");
            }
            token.setActive(false);
            deviceTokenRepository.save(token);
        });
    }

    private DeviceToken.Platform parsePlatform(String platform) {
        try {
            return DeviceToken.Platform.valueOf(platform.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Invalid platform: " + platform);
        }
    }
}
