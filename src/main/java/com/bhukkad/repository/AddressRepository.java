package com.bhukkad.repository;

import com.bhukkad.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AddressRepository extends JpaRepository<Address, Long> {
    List<Address> findByCustomerId(Long customerId);
    Optional<Address> findByCustomerIdAndIsDefaultTrue(Long customerId);

    @Query("SELECT a FROM Address a JOIN FETCH a.customer WHERE a.id = :id")
    Optional<Address> findByIdWithCustomer(@Param("id") Long id);
}