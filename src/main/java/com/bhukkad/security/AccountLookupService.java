package com.bhukkad.security;

import com.bhukkad.entity.Admin;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.entity.User;
import com.bhukkad.repository.AdminRepository;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.RestaurantOwnerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Role-routed identity resolution across the segregated per-role account
 * tables (V62). Replaces the legacy cross-role lookups on the shared users
 * table — credentials and contact PII now live on customers /
 * restaurant_owners / delivery_agents / admins, so identifier lookups hit a
 * single per-role index instead of a mixed-role table.
 *
 * <p>Resolution order is customer-first: the overwhelming majority of
 * identifier lookups (request authentication, phone sign-in) belong to
 * customers, so the common path costs exactly one indexed query. Admin
 * lookups run last — privileged accounts are the rarest.</p>
 *
 * <p>Returned entities are {@link User}-typed; callers that need
 * credentials or profile PII read them through {@link AccountFields}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountLookupService {

    private final CustomerRepository customerRepository;
    private final RestaurantOwnerRepository restaurantOwnerRepository;
    private final DeliveryAgentRepository deliveryAgentRepository;
    private final AdminRepository adminRepository;

    /** Resolves an account by email across all roles (customer-first). */
    public Optional<User> byEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        Optional<User> hit = customerRepository.findByEmail(email).map(User.class::cast);
        if (hit.isEmpty()) {
            hit = restaurantOwnerRepository.findByEmail(email).map(User.class::cast);
        }
        if (hit.isEmpty()) {
            hit = deliveryAgentRepository.findByEmail(email).map(User.class::cast);
        }
        if (hit.isEmpty()) {
            hit = adminRepository.findByEmail(email).map(User.class::cast);
        }
        return hit;
    }

    /** Resolves an account by phone number across all roles (customer-first). */
    public Optional<User> byPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return Optional.empty();
        }
        Optional<User> hit = customerRepository.findByPhoneNumber(phoneNumber).map(User.class::cast);
        if (hit.isEmpty()) {
            hit = restaurantOwnerRepository.findByPhoneNumber(phoneNumber).map(User.class::cast);
        }
        if (hit.isEmpty()) {
            hit = deliveryAgentRepository.findByPhoneNumber(phoneNumber).map(User.class::cast);
        }
        if (hit.isEmpty()) {
            hit = adminRepository.findByPhoneNumber(phoneNumber).map(User.class::cast);
        }
        return hit;
    }

    /**
     * Resolves an account by identifier (email or phone number) across all
     * roles. Used by request authentication where the JWT subject may be
     * either contact value.
     */
    public Optional<User> byIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        Optional<User> hit = customerRepository
                .findByEmailOrPhoneNumber(identifier, identifier).map(User.class::cast);
        if (hit.isEmpty()) {
            hit = restaurantOwnerRepository
                    .findByEmailOrPhoneNumber(identifier, identifier).map(User.class::cast);
        }
        if (hit.isEmpty()) {
            hit = deliveryAgentRepository
                    .findByEmailOrPhoneNumber(identifier, identifier).map(User.class::cast);
        }
        if (hit.isEmpty()) {
            hit = adminRepository
                    .findByEmailOrPhoneNumber(identifier, identifier).map(User.class::cast);
        }
        return hit;
    }

    /**
     * Whether ANY role already holds this email. Registration keeps the
     * legacy global-uniqueness semantics (one email cannot be both a customer
     * and an admin account) even though uniqueness is now enforced per table.
     */
    public boolean existsAnywhereByEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(customerRepository.existsByEmail(email))
                || Boolean.TRUE.equals(restaurantOwnerRepository.existsByEmail(email))
                || Boolean.TRUE.equals(deliveryAgentRepository.existsByEmail(email))
                || Boolean.TRUE.equals(adminRepository.existsByEmail(email));
    }

    /** Whether ANY role already holds this phone number (global uniqueness). */
    public boolean existsAnywhereByPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(customerRepository.existsByPhoneNumber(phoneNumber))
                || Boolean.TRUE.equals(restaurantOwnerRepository.existsByPhoneNumber(phoneNumber))
                || Boolean.TRUE.equals(deliveryAgentRepository.existsByPhoneNumber(phoneNumber))
                || Boolean.TRUE.equals(adminRepository.existsByPhoneNumber(phoneNumber));
    }

    /** Typed accessor for code paths that know the account is a customer. */
    public Optional<Customer> customerByEmail(String email) {
        return customerRepository.findByEmail(email);
    }

    /** Typed accessor for code paths that know the account is an admin. */
    public Optional<Admin> adminByEmail(String email) {
        return adminRepository.findByEmail(email);
    }

    /** Typed accessor for code paths that know the account is an owner. */
    public Optional<RestaurantOwner> ownerByEmail(String email) {
        return restaurantOwnerRepository.findByEmail(email);
    }

    /** Typed accessor for code paths that know the account is an agent. */
    public Optional<DeliveryAgent> agentByEmail(String email) {
        return deliveryAgentRepository.findByEmail(email);
    }
}
