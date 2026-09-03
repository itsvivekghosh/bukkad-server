package com.bhukkad.compliance;

import com.bhukkad.audit.AuditService;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.User;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.repository.AddressRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.UserRepository;
import com.bhukkad.security.AccountFields;
import com.bhukkad.security.AuthTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Erases or anonymizes a user's personal data on request (DPDP Act 2023 / GDPR
 * right to erasure).
 *
 * <p><strong>Strategy: anonymize, not delete.</strong> Financial and legal records
 * (orders, payments, settlements, audit trail) must be retained, so the user's
 * identifying attributes are overwritten in place instead of removing their rows.
 * After anonymization the account is deactivated, all sessions are revoked, saved
 * addresses are deleted and every consent purpose is revoked.</p>
 *
 * <p>The operation is idempotent — anonymizing an already-anonymized user is a
 * no-op that still returns success.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataDeletionService {

    /** TLD reserved by RFC 2606; cannot receive mail or collide with real emails. */
    static final String ANONYMIZED_EMAIL_DOMAIN = "@anon.invalid";

    private final UserRepository userRepository;
    private final AddressRepository addressRepository;
    private final OrderRepository orderRepository;
    private final AuthTokenService authTokenService;
    private final ConsentService consentService;
    private final AuditService auditService;

    /**
     * Anonymizes the given user. Returns the number of PII-bearing artifacts removed
     * (saved addresses) for reporting; 0 when the user was already anonymized.
     */
    @Transactional
    public int deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        int removedAddresses = 0;
        if (!isAnonymized(user)) {
            List<Address> addresses = addressRepository.findByCustomerId(userId);
            for (Address address : addresses) {
                if (orderRepository.countByDeliveryAddressId(address.getId()) > 0) {
                    // Orders FK-reference the address (delivery_address_id is NOT
                    // NULL), so the row must stay for financial-record integrity.
                    // Clear every PII-bearing field instead of deleting.
                    anonymizeAddress(address);
                } else {
                    addressRepository.delete(address);
                }
                removedAddresses++;
            }

            // Unique-constraint-safe placeholders derived from the immutable id.
            AccountFields.setEmail(user, "deleted-" + userId + ANONYMIZED_EMAIL_DOMAIN);
            AccountFields.setPhoneNumber(user, null);
            AccountFields.setFullName(user, "Deleted User");
            AccountFields.setProfileImageUrl(user, null);
            AccountFields.setTotpSecret(user, null);
            user.setTotpEnabled(false);
            user.setActive(false);
            userRepository.save(user);

            authTokenService.revokeAllRefreshTokens(userId);
            consentService.revokeAllConsents(userId);
        }

        auditService.recordEvent("USER_DATA_DELETED", "USER", String.valueOf(userId),
                null, "anonymized", userId);

        log.info("USER_DATA_ANONYMIZED | userId={} | addressesRemoved={}", userId, removedAddresses);
        return removedAddresses;
    }

    private static final String DELETED_MARKER = "deleted";

    /**
     * Clears the PII-bearing fields of an address whose row must be retained
     * because retained orders reference it. NOT NULL columns receive neutral
     * placeholders instead of nulls.
     */
    private void anonymizeAddress(Address address) {
        address.setAddressLine1(DELETED_MARKER);
        address.setAddressLine2(null);
        address.setCity(DELETED_MARKER);
        address.setState(DELETED_MARKER);
        address.setPincode("000000");
        address.setLandmark(null);
        address.setLabel(null);
        address.setLatitude(0.0);
        address.setLongitude(0.0);
        address.setIsDefault(false);
        addressRepository.save(address);
    }

    /**
     * True when the user row no longer carries direct identifiers.
     */
    public boolean isAnonymized(User user) {
        String email = AccountFields.email(user);
        return email != null && email.endsWith(ANONYMIZED_EMAIL_DOMAIN);
    }
}
