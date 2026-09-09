package com.bhukkad.identity.api;

import com.bhukkad.identity.domain.MembershipPlan;
import com.bhukkad.identity.domain.MembershipPlanRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Membership plan catalogue (monolith parity): public discovery of active
 * plans plus the customer-scoped membership status endpoint. Served at the
 * legacy {@code /api/v1/home/membership-plans} path via a gateway rewrite.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MembershipController {

    private final MembershipPlanRepository membershipPlanRepository;

    @GetMapping("/membership/plans")
    public List<MembershipPlan> plans() {
        return membershipPlanRepository.findByIsActiveTrue();
    }
}
