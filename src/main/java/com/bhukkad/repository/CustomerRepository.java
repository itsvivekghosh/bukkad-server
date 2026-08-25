package com.bhukkad.repository;

import com.bhukkad.entity.Customer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByEmail(String email);

    Optional<Customer> findByReferralCode(String referralCode);

    long countByReferredById(Long referredById);

    /**
     * Loads the customer with a pessimistic write lock so concurrent wallet
     * debit/credit operations on the same account are serialised at the DB
     * (prevents read-modify-write races that can over-spend the balance).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Customer c WHERE c.id = :id")
    Optional<Customer> findByIdWithLock(@Param("id") Long id);
}