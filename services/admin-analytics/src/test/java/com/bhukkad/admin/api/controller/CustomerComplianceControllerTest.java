package com.bhukkad.admin.api.controller;

import com.bhukkad.admin.domain.entity.ConsentRecord;
import com.bhukkad.admin.domain.service.ConsentService;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerComplianceControllerTest {

    @Mock private ConsentService consentService;
    @InjectMocks private CustomerComplianceController controller;

    private static com.bhukkad.common.security.TokenPrincipal principal(Long id, String scope) {
        return new com.bhukkad.common.security.TokenPrincipal(id, "u@example.com", scope);
    }

    private ConsentRecord record() {
        ConsentRecord r = new ConsentRecord();
        r.setUserId(7L);
        r.setPurpose("marketing");
        r.setGranted(true);
        return r;
    }

    @Test
    void getConsents_selfReadSucceeds() {
        when(consentService.getConsents(7L)).thenReturn(List.of(record()));

        assertThat(controller.getConsents(principal(7L, "CUSTOMER"), 7L)).hasSize(1);
    }

    @Test
    void getConsents_nullPrincipal_isUnauthorized() {
        assertThatThrownBy(() -> controller.getConsents(null, 7L))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void getConsents_principalWithoutUserId_isUnauthorized() {
        assertThatThrownBy(() -> controller.getConsents(principal(null, "CUSTOMER"), 7L))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void getConsents_otherCustomer_isDenied() {
        assertThatThrownBy(() -> controller.getConsents(principal(8L, "CUSTOMER"), 7L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("another customer");
    }

    @Test
    void getConsents_adminScopeMayReadAnyUser() {
        when(consentService.getConsents(7L)).thenReturn(List.of());

        assertThat(controller.getConsents(principal(99L, "ADMIN"), 7L)).isEmpty();
    }

    @Test
    void setConsent_validPurpose_mapsBodyFields() {
        when(consentService.setConsent(eq(7L), eq("marketing"), eq(true), eq("portal"))).thenReturn(record());

        controller.setConsent(principal(7L, "CUSTOMER"), 7L,
                Map.of("purpose", "marketing", "granted", Boolean.TRUE, "source", "portal"));

        verify(consentService).setConsent(7L, "marketing", true, "portal");
    }

    @Test
    void setConsent_missingGrantedDefaultsFalseAndSourceDefaultsToPortal() {
        when(consentService.setConsent(7L, "notifications", false, "customer-portal")).thenReturn(record());

        controller.setConsent(principal(7L, "CUSTOMER"), 7L, Map.of("purpose", "notifications"));

        verify(consentService).setConsent(eq(7L), eq("notifications"), eq(false), eq("customer-portal"));
    }

    @Test
    void setConsent_invalidPurpose_isRejected() {
        assertThatThrownBy(() -> controller.setConsent(principal(7L, "CUSTOMER"), 7L,
                Map.of("purpose", "Bad Purpose!")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid consent purpose");
    }

    @Test
    void setConsent_missingPurposeKey_isRejected_notPersistedAsNullLiteral() {
        // Regression: String.valueOf(null) yielded the literal "null", which
        // matched the format regex and persisted a junk consent record.
        assertThatThrownBy(() -> controller.setConsent(principal(7L, "CUSTOMER"), 7L,
                Map.of("granted", Boolean.TRUE)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid consent purpose");

        verify(consentService, never()).setConsent(anyLong(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void setConsent_explicitNullPurposeValue_isRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("purpose", null);
        body.put("granted", true);

        assertThatThrownBy(() -> controller.setConsent(principal(7L, "CUSTOMER"), 7L, body))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid consent purpose");
    }

    @Test
    void setConsent_blankPurpose_isRejected() {
        assertThatThrownBy(() -> controller.setConsent(principal(7L, "CUSTOMER"), 7L,
                Map.of("purpose", "   ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid consent purpose");
    }

    @Test
    void setConsent_explicitStringPurpose_behaviorUnchanged() {
        // Only absent/null/blank/invalid inputs are rejected; any string that
        // satisfies the format regex is still forwarded verbatim.
        when(consentService.setConsent(eq(7L), eq("marketing"), eq(true), eq("customer-portal")))
                .thenReturn(record());

        controller.setConsent(principal(7L, "CUSTOMER"), 7L,
                Map.of("purpose", "marketing", "granted", Boolean.TRUE));

        verify(consentService).setConsent(7L, "marketing", true, "customer-portal");
    }

    @Test
    void consentSatisfied_delegatesToService() {
        when(consentService.allConsented(7L, "marketing")).thenReturn(true);

        assertThat(controller.consentSatisfied(principal(7L, "CUSTOMER"), 7L, "marketing")).isTrue();
    }

    @Test
    void revokeAll_requiresOwnership_andDelegates() {
        controller.revokeAll(principal(7L, "CUSTOMER"), 7L);
        verify(consentService).revokeAllConsents(7L);

        assertThatThrownBy(() -> controller.revokeAll(principal(8L, "CUSTOMER"), 7L))
                .isInstanceOf(AccessDeniedException.class);
    }
}
