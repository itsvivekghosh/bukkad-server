package com.bhukkad.identity.api;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.JwtRevocationService;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.identity.domain.Address;
import com.bhukkad.identity.domain.AddressRepository;
import com.bhukkad.identity.domain.Customer;
import com.bhukkad.identity.domain.CustomerRepository;
import com.bhukkad.identity.domain.FavoriteRestaurant;
import com.bhukkad.identity.service.AccountProfileService;
import com.bhukkad.identity.service.AddressService;
import com.bhukkad.identity.service.IdentityService;
import com.bhukkad.identity.referral.ReferralService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-scoped customer surface used by the installed apps (monolith parity):
 * the acting customer is ALWAYS the authenticated subject (no IDOR headroom).
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerSelfController {

    private final CustomerRepository customerRepository;
    private final AddressService addressService;
    private final AddressRepository addressRepository;
    private final ReferralService referralService;
    private final AccountProfileService profileService;
    /** P1: self-service account deletion must invalidate issued access tokens immediately. */
    private final JwtRevocationService revocationService;

    public CustomerSelfController(CustomerRepository customerRepository,
                                    AddressService addressService,
                                    AddressRepository addressRepository,
                                    ReferralService referralService,
                                    AccountProfileService profileService,
                                    JwtRevocationService revocationService) {
        this.customerRepository = customerRepository;
        this.addressService = addressService;
        this.addressRepository = addressRepository;
        this.referralService = referralService;
        this.profileService = profileService;
        this.revocationService = revocationService;
    }

    @GetMapping("/profile")
    @Transactional(readOnly = true)
    public Map<String, Object> profile(@AuthenticationPrincipal TokenPrincipal principal) {
        Customer c = customer(principal);
        return Map.of("id", c.getId(), "fullName", nullSafe(c.getFullName()),
                "email", nullSafe(c.getEmail()), "phoneNumber", nullSafe(c.getPhoneNumber()));
    }

    @PutMapping("/profile")
    @Transactional
    public Map<String, Object> updateProfile(@AuthenticationPrincipal TokenPrincipal principal,
                                             @RequestParam(required = false) String fullName,
                                             @RequestBody(required = false) Map<String, Object> body) {
        Customer c = customer(principal);
        String name = fullName != null ? fullName
                : body != null && body.get("fullName") != null ? String.valueOf(body.get("fullName")) : null;
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new com.bhukkad.common.error.BusinessException("fullName is required (max 120 chars)");
        }
        c.setFullName(name.trim());
        customerRepository.save(c);
        return profile(principal);
    }

    @GetMapping("/addresses")
    @Transactional(readOnly = true)
    public List<Address> addresses(@AuthenticationPrincipal TokenPrincipal principal) {
        return addressService.listAddresses(subjectId(principal));
    }

    @PostMapping("/addresses")
    public Address addAddress(@AuthenticationPrincipal TokenPrincipal principal,
                              @Valid @RequestBody IdentityController.AddressRequest request) {
        return addressService.addAddress(subjectId(principal),
                         new AddressService.AddressInput(request.label(), request.line1(), request.city(),
                                 request.state(), request.zipCode(), request.isDefault()));
    }

    @GetMapping("/referral")
    @Transactional(readOnly = true)
    public Map<String, Object> referralInfo(@AuthenticationPrincipal TokenPrincipal principal) {
        Long customerId = subjectId(principal);
        com.bhukkad.identity.dto.response.ReferralInfoResponse info =
                referralService.getReferralInfo(customerId);
        return Map.of("referralCode", info.getReferralCode(),
                "referralsCount", info.getReferralsCount(),
                "referralBonusEarned", info.getReferralBonusEarned());
    }

    // ------------------------------------------------------------------
    // Favorites (self-scoped aliases of the id-scoped /customers/{id} path)
    // ------------------------------------------------------------------

    @PostMapping("/favorites/{restaurantId}")
    public FavoriteRestaurant addFavorite(@AuthenticationPrincipal TokenPrincipal principal,
                                          @PathVariable Long restaurantId) {
        return profileService.addFavorite(subjectId(principal), restaurantId);
    }

    @GetMapping("/favorites")
    @Transactional(readOnly = true)
    public List<FavoriteRestaurant> favorites(@AuthenticationPrincipal TokenPrincipal principal) {
        return profileService.favorites(subjectId(principal));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/favorites/{restaurantId}")
    public Map<String, String> removeFavorite(@AuthenticationPrincipal TokenPrincipal principal,
                                              @PathVariable Long restaurantId) {
        profileService.removeFavorite(subjectId(principal), restaurantId);
        return Map.of("message", "favorite removed");
    }

    // ------------------------------------------------------------------
    // Address maintenance (update / set-default / delete)
    // ------------------------------------------------------------------

    @PutMapping("/addresses/{addressId}")
    @Transactional
    public Address updateAddress(@AuthenticationPrincipal TokenPrincipal principal,
                                 @PathVariable Long addressId,
                                 @RequestBody(required = false) UpdateAddressRequest request) {
        Long customerId = subjectId(principal);
        Address address = ownedAddress(customerId, addressId);
        if (request != null) {
            if (request.label() != null) address.setLabel(request.label());
            if (request.line1() != null && !request.line1().isBlank()) address.setLine1(request.line1());
            if (request.addressLine1() != null && !request.addressLine1().isBlank()) {
                address.setLine1(request.addressLine1());
            }
            if (request.city() != null && !request.city().isBlank()) address.setCity(request.city());
            if (request.state() != null) address.setState(request.state());
            if (request.zipCode() != null) address.setZipCode(request.zipCode());
            if (request.pincode() != null) address.setZipCode(request.pincode());
            if (request.landmark() != null) address.setLandmark(request.landmark());
            if (request.isDefault() != null) address.setIsDefault(request.isDefault());
        }
        return addressRepository.save(address);
    }

    @PutMapping("/addresses/{addressId}/set-default")
    @Transactional
    public Address setDefaultAddress(@AuthenticationPrincipal TokenPrincipal principal,
                                     @PathVariable Long addressId) {
        Long customerId = subjectId(principal);
        addressRepository.findByCustomerId(customerId).forEach(a -> a.setIsDefault(false));
        Address address = ownedAddress(customerId, addressId);
        address.setIsDefault(true);
        return addressRepository.save(address);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/addresses/{addressId}")
    @Transactional
    public Map<String, String> deleteAddress(@AuthenticationPrincipal TokenPrincipal principal,
                                             @PathVariable Long addressId) {
        Long customerId = subjectId(principal);
        Address address = ownedAddress(customerId, addressId);
        addressRepository.delete(address);
        return Map.of("message", "address deleted");
    }

    private Address ownedAddress(Long customerId, Long addressId) {
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found: " + addressId));
        if (!customerId.equals(address.getCustomerId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Cannot modify another customer's address");
        }
        return address;
    }

    /** Partial update body: accepts both the legacy and monolith field names. */
    public record UpdateAddressRequest(String label, String line1, String addressLine1,
                                       String city, String state, String zipCode,
                                       String pincode, String landmark, Boolean isDefault) {}

    // ------------------------------------------------------------------
    // Account deletion (GDPR erasure)
    // ------------------------------------------------------------------

    /**
     * Soft-deletes the caller's account: the row is deactivated and stripped
     * of contact data so a re-registration with the same email works, while
     * historical orders keep their customer reference for accounting.
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/account")
    @Transactional
    public Map<String, String> deleteAccount(@AuthenticationPrincipal TokenPrincipal principal) {
        Customer c = customer(principal);
        c.setIsActive(false);
        String suffix = "-del-" + c.getId();
        if (c.getEmail() != null) {
            c.setEmail("deleted" + suffix + "@bhukkad.invalid");
        }
        if (c.getPhoneNumber() != null) {
            // users.phone_number is varchar(15): keep the tombstone compact.
            c.setPhoneNumber("X" + Math.abs((c.getId() * 7919) % 1_000_000_000));
        }
        if (c.getFullName() != null) {
            c.setFullName("Deleted User");
        }
        customerRepository.save(c);
        revocationService.revokeTokensIssuedBefore(c.getId());
        return Map.of("message", "Account deleted");
    }

    private Customer customer(TokenPrincipal principal) {
        return customerRepository.findById(subjectId(principal))
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
    }

    private static Long subjectId(TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated customer required");
        }
        return principal.userId();
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }
}
