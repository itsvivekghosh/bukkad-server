package com.bhukkad.identity.domain.service;

import com.bhukkad.identity.api.dto.request.UpdateProfileRequest;
import com.bhukkad.identity.api.dto.response.CustomerProfileResponse;
import com.bhukkad.identity.domain.entity.Customer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service interface for customer account profile operations.
 */
public interface AccountProfileService {

	CustomerProfileResponse getProfile(UUID customerId);

	CustomerProfileResponse updateProfile(UUID customerId, UpdateProfileRequest request);

	void deleteAccount(UUID customerId);

	Optional<Customer> findById(UUID customerId);

	List<Customer> findAll();

	Customer save(Customer customer);
}