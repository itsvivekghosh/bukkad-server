package com.bhukkad.identity.api;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.identity.domain.Address;
import com.bhukkad.identity.domain.FavoriteRestaurant;
import com.bhukkad.identity.service.AccountProfileService;
import com.bhukkad.identity.service.ConsentService;
import com.bhukkad.identity.service.IdentityService;
import com.bhukkad.identity.service.AddressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Per-endpoint unit matrix for the identity customer surfaces: ownership
 * (subject-or-admin) on addresses, favorites, and consent records.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdentityCustomerEndpointSecurityTest {

    private static TokenPrincipal principal(long userId, String scope) {
        return new TokenPrincipal(userId, "u@t.test", scope);
    }

    @Mock private IdentityService identityService;
    @Mock private AddressService addressService;
    @Mock private AccountProfileService profileService;
    @Mock private ConsentService consentService;
    @InjectMocks private CustomerController customerController;
    @InjectMocks private IdentityController identityController;

    private IdentityController.AddressRequest addressRequest;

    @BeforeEach
    void setUp() {
        addressRequest = new IdentityController.AddressRequest(
                "home", "1 Main St", "Pune", "MH", "411001", false);
    }

    // ---- IdentityController address endpoints ----

    @Test
    void addAddress_crossCustomer_throws() {
        assertThatThrownBy(() -> identityController.addAddress(principal(2L, "CUSTOMER"),
                1L, addressRequest))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void addAddress_ownCustomer_allowed() {
        when(addressService.addAddress(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new Address());

        assertThat(identityController.addAddress(principal(1L, "CUSTOMER"), 1L, addressRequest))
                .isNotNull();
    }

    @Test
    void addAddress_adminAcrossCustomers_allowed() {
        when(addressService.addAddress(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new Address());

        assertThat(identityController.addAddress(principal(99L, "ADMIN"), 1L, addressRequest))
                .isNotNull();
    }

    @Test
    void listAddresses_crossCustomer_throws() {
        assertThatThrownBy(() -> identityController.listAddresses(principal(2L, "CUSTOMER"), 1L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void changePassword_unauthenticated_throws() {
        IdentityController.ChangePasswordRequest request =
                new IdentityController.ChangePasswordRequest("old", "NewSecret123!");
        assertThatThrownBy(() -> identityController.changePassword(null, request))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    // ---- CustomerController favorites + consent ----

    @Test
    void favorites_crossCustomer_throws() {
        assertThatThrownBy(() -> customerController.favorites(principal(2L, "CUSTOMER"), 1L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void favorites_ownCustomer_allowed() {
        when(profileService.favorites(1L)).thenReturn(List.of());

        assertThat(customerController.favorites(principal(1L, "CUSTOMER"), 1L)).isEmpty();
    }

    @Test
    void addFavorite_crossCustomer_throws() {
        assertThatThrownBy(() -> customerController.addFavorite(principal(2L, "CUSTOMER"),
                1L, 5L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void consent_crossCustomer_write_throws() {
        assertThatThrownBy(() -> customerController.recordConsent(principal(2L, "CUSTOMER"),
                1L, new CustomerController.ConsentRequest("marketing", true)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void consent_ownCustomer_write_allowed() {
        when(consentService.record(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("marketing"),
                org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new com.bhukkad.identity.domain.ConsentRecord());

        assertThat(customerController.recordConsent(principal(1L, "CUSTOMER"), 1L,
                new CustomerController.ConsentRequest("marketing", true))).isNotNull();
    }

    @Test
    void consent_crossCustomer_read_throws() {
        assertThatThrownBy(() -> customerController.hasConsent(principal(2L, "CUSTOMER"),
                1L, "marketing"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
