package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.Address;

import com.bhukkad.identity.domain.entity.Address;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AddressRepository extends JpaRepository<Address, Long> {
    Page<Address> findByCustomerId(Long customerId, Pageable pageable);

    /**
     * @deprecated Use {@link #findByCustomerId(Long, Pageable)} instead for bounded results.
     */
    @Deprecated
    List<Address> findByCustomerId(Long customerId);
}