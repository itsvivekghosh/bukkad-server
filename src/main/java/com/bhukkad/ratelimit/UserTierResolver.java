package com.bhukkad.ratelimit;

import com.bhukkad.entity.CustomerMembership;
import com.bhukkad.entity.User;
import com.bhukkad.repository.CustomerMembershipRepository;
import com.bhukkad.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Resolves the rate-limit tier for the current caller. Authenticated users
 * with an active membership are rated by their plan tier level
 * (0=free, 1=silver, 2=gold, 3=platinum); everyone else is "free".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserTierResolver {

    public static final String TIER_FREE = "free";

    private final SecurityUtils securityUtils;
    private final CustomerMembershipRepository customerMembershipRepository;

    public String resolveCurrentTier() {
        User user;
        try {
            user = securityUtils.getCurrentUser();
        } catch (Exception ex) {
            return TIER_FREE;
        }
        if (user == null) {
            return TIER_FREE;
        }
        if (user.getRole() == User.UserRole.ADMIN) {
            return "platinum";
        }
        Optional<CustomerMembership> membership =
                customerMembershipRepository.findActiveMembership(user.getId(), LocalDateTime.now());
        if (membership.isEmpty()) {
            return TIER_FREE;
        }
        Integer tierLevel = membership.get().getPlan().getTierLevel();
        return switch (tierLevel == null ? 0 : tierLevel) {
            case 1 -> "silver";
            case 2 -> "gold";
            case 3 -> "platinum";
            default -> TIER_FREE;
        };
    }
}
