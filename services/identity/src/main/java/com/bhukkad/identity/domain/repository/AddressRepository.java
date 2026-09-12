package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.Address;

import com.bhukkad.identity.domain.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AddressRepository extends JpaRepository<Address, Long> {
    List<Address> findByCustomerId(Long customerId);
}
