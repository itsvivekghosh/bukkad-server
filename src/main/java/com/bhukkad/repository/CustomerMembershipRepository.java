package com.bhukkad.repository;

import com.bhukkad.entity.CustomerMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface CustomerMembershipRepository extends JpaRepository<CustomerMembership, Long> {

    @Query("""
            SELECT m FROM CustomerMembership m
            WHERE m.customer.id = :customerId
              AND m.status = com.bhukkad.entity.CustomerMembership$MembershipStatus.ACTIVE
              AND m.startsAt <= :now
              AND m.endsAt >= :now
            ORDER BY m.endsAt DESC
            """)
    Optional<CustomerMembership> findActiveMembership(Long customerId, LocalDateTime now);
}
