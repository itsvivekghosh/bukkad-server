package com.bhukkad.security;

import com.bhukkad.entity.User;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecurityUtils {

    private final UserRepository userRepository;

    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("User not authenticated");
        }

        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("User not found"));
    }

    public Long getCurrentUserId() {
        return getCurrentUser().getId();
    }

    public boolean isCurrentUser(Long userId) {
        return getCurrentUserId().equals(userId);
    }
}