package com.bhukkad.repository;

import com.bhukkad.entity.CustomerMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

@Repository
public interface CustomerMembershipRepository extends JpaRepository<CustomerMembership, Long> {

    @Query("""
            SELECT m FROM CustomerMembership m
            JOIN FETCH m.plan
            WHERE m.customer.id = :customerId
              AND m.status = com.bhukkad.entity.CustomerMembership$MembershipStatus.ACTIVE
              AND m.startsAt <= :now
              AND m.endsAt >= :now
            ORDER BY m.endsAt DESC
            """)
    Optional<CustomerMembership> findActiveMembership(Long customerId, LocalDateTime now);

    @Query("SELECT COUNT(DISTINCT o.id) FROM Order o WHERE o.customer.id = :referrerId AND o.status = 'DELIVERED' AND o.createdAt >= :startDate")
    long countReferralsByReferrerThisMonth(Long referrerId, LocalDateTime startDate);

    @Query("SELECT COUNT(m.id) FROM CustomerMembership m WHERE m.plan.id = :planId AND m.status = 'ACTIVE'")
    long countActiveMembersByPlan(Long planId);
}
