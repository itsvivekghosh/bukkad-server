package com.bhukkad.security;

import com.bhukkad.entity.User;
import com.bhukkad.common.error.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecurityUtils {

    private static final Logger log = LoggerFactory.getLogger(SecurityUtils.class);

    private final AccountLookupService accountLookupService;

    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("User not authenticated");
        }

        String email = authentication.getName();

        if ("anonymousUser".equals(email)) {
            throw new UnauthorizedException("User not authenticated");
        }

        return accountLookupService.byIdentifier(email)
                .orElseThrow(() -> new UnauthorizedException("User not found: " + email));
    }

    public Long getCurrentUserId() {
        return getCurrentUser().getId();
    }

    public String getCurrentUserEmail() {
        return AccountFields.email(getCurrentUser());
    }

    public String getCurrentUserPhoneNumber() {
        return AccountFields.phoneNumber(getCurrentUser());
    }

    public boolean isCurrentUser(Long userId) {
        try {
            return getCurrentUserId().equals(userId);
        } catch (Exception e) {
            return false;
        }
    }
}
