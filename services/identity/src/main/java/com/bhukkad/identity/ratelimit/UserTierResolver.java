package com.bhukkad.identity.ratelimit;

import com.bhukkad.identity.domain.CustomerMembership;
import com.bhukkad.identity.domain.CustomerMembershipRepository;
import com.bhukkad.identity.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserTierResolver {

    public static final String TIER_FREE = "free";

    private final SecurityUtils securityUtils;
    private final CustomerMembershipRepository customerMembershipRepository;

    public String resolveCurrentTier() {
        Long userId;
        try {
            userId = securityUtils.getCurrentUserId();
        } catch (Exception ex) {
            return TIER_FREE;
        }
        if (userId == null) {
            return TIER_FREE;
        }
        Optional<CustomerMembership> membership =
                customerMembershipRepository.findActiveMembership(userId, LocalDateTime.now());
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
