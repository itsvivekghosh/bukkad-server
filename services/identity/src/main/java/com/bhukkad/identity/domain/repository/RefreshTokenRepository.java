package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.RefreshToken;

import com.bhukkad.identity.domain.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Live sessions of a customer: not revoked, not expired, newest first. */
    @Query("SELECT t FROM RefreshToken t WHERE t.customerId = :customerId "
            + "AND t.revokedAt IS NULL AND t.expiresAt > :now ORDER BY t.createdAt DESC")
    List<RefreshToken> findActiveByCustomerId(@Param("customerId") Long customerId, @Param("now") Instant now);

    /**
     * Conditional single-row revocation: only revives into "revoked" if still
     * live, so a loser in a concurrent double-submit sees 0 rows affected and
     * can fail rotation closed (token reuse detection).
     */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now "
            + "WHERE t.tokenHash = :tokenHash AND t.revokedAt IS NULL")
    int revokeIfLive(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /** Kills every live session of a customer (credential change, deactivation). */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now "
            + "WHERE t.customerId = :customerId AND t.revokedAt IS NULL")
    int revokeAllByCustomer(@Param("customerId") Long customerId, @Param("now") Instant now);

    /**
     * Token-reuse detection: kills every live member of a rotation family at
     * once, so an attacker replaying a revoked token cannot keep either copy.
     */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now "
            + "WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeAllByFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    /** Housekeeping: tombstone past-expiry rows that were never revoked. */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now "
            + "WHERE t.revokedAt IS NULL AND t.expiresAt <= :now")
    int revokeExpired(@Param("now") Instant now);

    /** Housekeeping: drop rows past expiry entirely. */
    long deleteByExpiresAtBefore(Instant cutoff);
}
