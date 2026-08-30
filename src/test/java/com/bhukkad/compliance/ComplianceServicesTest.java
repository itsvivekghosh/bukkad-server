package com.bhukkad.compliance;

import com.bhukkad.audit.AuditService;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.User;
import com.bhukkad.repository.AddressRepository;
import com.bhukkad.repository.FraudEventRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.UserRepository;
import com.bhukkad.security.AuthTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComplianceServicesTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private AuthTokenService authTokenService;
    @Mock
    private ConsentService consentService;
    @Mock
    private AuditService auditService;
    @Mock
    private DataExportRequestRepository dataExportRequestRepository;
    @Mock
    private FraudEventRepository fraudEventRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    private DataDeletionService deletionService;
    private DataRetentionService retentionService;

    @BeforeEach
    void setUp() {
        deletionService = new DataDeletionService(userRepository, addressRepository,
                orderRepository, authTokenService, consentService, auditService);
        retentionService = new DataRetentionService(new ComplianceProperties(),
                dataExportRequestRepository, fraudEventRepository, transactionTemplate);
    }

    // ------------------------- DataDeletionService -------------------------

    @Test
    void deleteUser_anonymizesPiiAndRevokesAccess() {
        Customer user = new Customer();
        user.setId(3L);
        user.setEmail("victim@example.com");
        user.setPhoneNumber("9876543210");
        user.setFullName("Victim Name");
        user.setTotpEnabled(true);
        when(userRepository.findById(3L)).thenReturn(Optional.of(user));
        Address address = new Address();
        when(addressRepository.findByCustomerId(3L)).thenReturn(List.of(address));

        int removed = deletionService.deleteUser(3L);

        assertEquals(1, removed);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        Customer saved = (Customer) captor.getValue();
        assertTrue(saved.getEmail().startsWith("deleted-3"));
        assertTrue(saved.getEmail().endsWith("@anon.invalid"));
        assertFalse(saved.getActive());
        assertFalse(saved.getTotpEnabled());
        assertEquals("Deleted User", saved.getFullName());
        verify(authTokenService).revokeAllRefreshTokens(3L);
        verify(consentService).revokeAllConsents(3L);
        verify(auditService).recordEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void deleteUser_isIdempotentForAlreadyAnonymizedUsers() {
        Customer user = new Customer();
        user.setId(4L);
        user.setEmail("deleted-4@anon.invalid");
        user.setActive(false);
        when(userRepository.findById(4L)).thenReturn(Optional.of(user));

        int removed = deletionService.deleteUser(4L);

        assertEquals(0, removed);
        verify(userRepository, never()).save(any());
        verify(authTokenService, never()).revokeAllRefreshTokens(4L);
    }

    @Test
    void deleteUser_anonymizesOrderReferencedAddressInsteadOfDeleting() {
        // Regression: addresses referenced by retained orders (FK) cannot be
        // deleted; their PII must be cleared in place to avoid a 500.
        Customer user = new Customer();
        user.setId(5L);
        user.setEmail("victim5@example.com");
        user.setPhoneNumber("9876543211");
        user.setFullName("Victim Five");
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));

        Address referenced = new Address();
        referenced.setId(77L);
        referenced.setAddressLine1("PII Line 1");
        referenced.setCity("PII City");
        referenced.setPincode("560001");
        when(addressRepository.findByCustomerId(5L)).thenReturn(List.of(referenced));
        when(orderRepository.countByDeliveryAddressId(77L)).thenReturn(1L);

        int removed = deletionService.deleteUser(5L);

        assertEquals(1, removed);
        // The row is retained but every PII field is cleared (NOT NULL columns
        // receive neutral placeholders).
        verify(addressRepository, never()).delete(referenced);
        assertEquals("deleted", referenced.getAddressLine1());
        assertEquals("deleted", referenced.getCity());
        assertEquals("000000", referenced.getPincode());
        verify(addressRepository).save(referenced);
    }

    @Test
    void deleteUser_deletesUnreferencedAddress() {
        Customer user = new Customer();
        user.setId(6L);
        user.setEmail("victim6@example.com");
        user.setPhoneNumber("9876543212");
        user.setFullName("Victim Six");
        when(userRepository.findById(6L)).thenReturn(Optional.of(user));

        Address unreferenced = new Address();
        unreferenced.setId(78L);
        when(addressRepository.findByCustomerId(6L)).thenReturn(List.of(unreferenced));
        when(orderRepository.countByDeliveryAddressId(78L)).thenReturn(0L);

        deletionService.deleteUser(6L);

        verify(addressRepository).delete(unreferenced);
        verify(addressRepository, never()).save(unreferenced);
    }

    // ------------------------- DataRetentionService -------------------------

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void purgeExpiredData_deletesBothArtifactTypesPastCutoff() {
        ComplianceProperties props = new ComplianceProperties();
        props.setEnabled(true);
        props.setRetentionDays(90);
        retentionService = new DataRetentionService(props,
                dataExportRequestRepository, fraudEventRepository, transactionTemplate);

        when(dataExportRequestRepository.deleteByRequestedAtBefore(any(LocalDateTime.class))).thenReturn(2);
        when(fraudEventRepository.deleteByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(7L);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = (TransactionCallback<Integer>)
                    invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        retentionService.purgeExpiredData();

        verify(dataExportRequestRepository).deleteByRequestedAtBefore(any(LocalDateTime.class));
        verify(fraudEventRepository).deleteByCreatedAtBefore(any(LocalDateTime.class));
    }

    @Test
    void purgeExpiredData_skipsEverythingWhenComplianceDisabled() {
        ComplianceProperties props = new ComplianceProperties();
        props.setEnabled(false);
        retentionService = new DataRetentionService(props,
                dataExportRequestRepository, fraudEventRepository, transactionTemplate);

        retentionService.purgeExpiredData();

        verify(dataExportRequestRepository, never()).deleteByRequestedAtBefore(any());
        verify(fraudEventRepository, never()).deleteByCreatedAtBefore(any());
    }
}
