package com.bhukkad.identity;

import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.identity.domain.Customer;
import com.bhukkad.identity.domain.CustomerRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityUtils {

    private final CustomerRepository customerRepository;

    public SecurityUtils(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public Customer getCurrentUser() {
        TokenPrincipal principal = getCurrentPrincipal();
        return customerRepository.findById(principal.userId())
                .orElseThrow(() -> new UnauthorizedException("User not found: " + principal.userId()));
    }

    public Long getCurrentUserId() {
        return getCurrentPrincipal().userId();
    }

    public String getCurrentUserEmail() {
        return getCurrentPrincipal().email();
    }

    public String getCurrentUserPhoneNumber() {
        return getCurrentUser().getPhoneNumber();
    }

    public boolean isCurrentUser(Long userId) {
        try {
            return getCurrentUserId().equals(userId);
        } catch (Exception e) {
            return false;
        }
    }

    private TokenPrincipal getCurrentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("User not authenticated");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof TokenPrincipal tokenPrincipal) {
            return tokenPrincipal;
        }
        throw new UnauthorizedException("User not authenticated");
    }
}
