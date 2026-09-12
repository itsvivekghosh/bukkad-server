package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.CustomerMembership;

import com.bhukkad.identity.domain.entity.CustomerMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Port of the monolith {@code com.bhukkad.repository.CustomerMembershipRepository}
 * (WAVE 2). The monolith's {@code countReferralsByReferrerThisMonth} is not
 * ported: its JPQL joins the order aggregate, which belongs to the order
 * domain, not identity.
 */
public interface CustomerMembershipRepository extends JpaRepository<CustomerMembership, Long> {

    @Query("""
            SELECT m FROM CustomerMembership m
            JOIN FETCH m.plan
            WHERE m.customer.id = :customerId
              AND m.status = com.bhukkad.identity.domain.entity.CustomerMembership$MembershipStatus.ACTIVE
              AND m.startsAt <= :now
              AND m.endsAt >= :now
            ORDER BY m.endsAt DESC
            """)
    Optional<CustomerMembership> findActiveMembership(@Param("customerId") Long customerId, LocalDateTime now);

    @Query("SELECT COUNT(m.id) FROM CustomerMembership m WHERE m.plan.id = :planId AND m.status = 'ACTIVE'")
    long countActiveMembersByPlan(@Param("planId") Long planId);
}
