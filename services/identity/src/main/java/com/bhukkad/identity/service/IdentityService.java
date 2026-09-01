package com.bhukkad.identity.service;

import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.identity.domain.Customer;
import com.bhukkad.identity.domain.CustomerRepository;
import com.bhukkad.identity.security.JwtService;
import com.bhukkad.identity.security.PasswordService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer registration and authentication (plan §8: identity is the
 * authN source of truth).
 */
@Service
@RequiredArgsConstructor
public class IdentityService {

    private final CustomerRepository customerRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;
    private final IdentityEventPublisher eventPublisher;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public Customer register(String email, String phoneNumber, String fullName, String rawPassword) {
        if (customerRepository.findByEmail(email).isPresent()) {
            throw new DuplicateRequestException("Email already registered: " + email);
        }
        Customer customer = new Customer();
        customer.setEmail(email);
        customer.setPhoneNumber(phoneNumber);
        customer.setFullName(fullName);
        customer.setPasswordHash(passwordService.hash(rawPassword));
        customer.setEmailVerified(false);
        customer.setIsActive(true);
        customer = customerRepository.save(customer);

        // The customers table is the auth profile; favorites/consent FK to the
        // JOINED `users` base table (id shared with the customer row) so every
        // identity API keyed by customerId resolves.
         entityManager.createNativeQuery(
                 "INSERT INTO users (id, role, active, email_verified, phone_verified, " +
                         "profile_completed, totp_enabled, created_at, updated_at) " +
                         "OVERRIDING SYSTEM VALUE " +
                         "VALUES (:id, 'CUSTOMER', true, false, false, false, false, now(), now())")
                 .setParameter("id", customer.getId())
                 .executeUpdate();

        eventPublisher.customerRegistered(customer.getId(), email, fullName);
        return customer;
    }

    @Transactional(readOnly = true)
    public LoginResult login(String email, String rawPassword) {
        Customer customer = customerRepository.findByEmailAndIsActiveTrue(email)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid email or password"));
        if (!passwordService.matches(rawPassword, customer.getPasswordHash())) {
            throw new ResourceNotFoundException("Invalid email or password");
        }
        String token = jwtService.issue(customer.getId(), customer.getEmail());
        return new LoginResult(token, customer.getId(), customer.getFullName());
    }

    /**
     * Rotates a still-valid access token into a fresh one. The presented token
     * is verified (signature + expiry) via introspection; if it is valid, a new
     * token is issued for the same customer. Invalid/expired tokens are rejected
     * with the same 404-as-401 response the login path uses (no user enumeration).
     */
    @Transactional(readOnly = true)
    public LoginResult refresh(String token) {
        JwtService.IntrospectionResult result = jwtService.introspect(token);
        if (!result.valid() || result.customerId() == null) {
            throw new ResourceNotFoundException("Invalid or expired token");
        }
        Customer customer = customerRepository.findById(result.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Invalid or expired token"));
        String freshToken = jwtService.issue(customer.getId(), customer.getEmail());
        return new LoginResult(freshToken, customer.getId(), customer.getFullName());
    }

    public record LoginResult(String token, Long customerId, String fullName) {
    }
}