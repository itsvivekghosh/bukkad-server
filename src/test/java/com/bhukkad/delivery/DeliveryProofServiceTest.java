package com.bhukkad.delivery;

import com.bhukkad.dto.request.DeliveryProofVerifyRequest;
import com.bhukkad.dto.response.DeliveryProofResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderDeliveryProof;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.notification.sms.SmsSender;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.OrderDeliveryProofRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeliveryProofService}.
 *
 * <h2>Why the properties object is real and not a mock</h2>
 * <p>{@link DeliveryProofProperties} is a plain configuration holder, and almost every method in the
 * service reads two or three of its getters. Mocking it would mean stubbing those getters in every
 * test and would trip Mockito's strict-stub checking the moment a guard short-circuits before a
 * getter is reached. A real instance is constructed instead and mutated per test, which also makes
 * the enabled/enforced rollout matrix readable.</p>
 *
 * <h2>What these tests are actually protecting</h2>
 * <p>The security properties of this feature are easy to regress silently, so each one is pinned:
 * the plaintext code reaches only the SMS channel and never the API response, a wrong guess is
 * persisted <i>before</i> the rejection so the counter cannot be rolled back, verification fails
 * closed on expiry and exhaustion, and every entry point re-checks that the caller is the rider
 * assigned to the order rather than trusting the controller's role annotation.</p>
 */
@ExtendWith(MockitoExtension.class)
class DeliveryProofServiceTest {

    private static final Long ORDER_ID = 5L;
    private static final Long AGENT_ID = 7L;
    private static final String HASH = "$2a$10$storedhashvaluefordeliveryproofcodexxxxxxxxxxxxxxxxxxxxxxx";

    @Mock
    private OrderDeliveryProofRepository proofRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private DeliveryAgentRepository deliveryAgentRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private SmsSender smsSender;
    @Mock
    private DeliveryProofPhotoStorageService photoStorageService;
    @Mock
    private SecurityUtils securityUtils;

    private DeliveryProofProperties properties;
    private DeliveryProofService service;

    @BeforeEach
    void setUp() {
        properties = new DeliveryProofProperties();
        service = new DeliveryProofService(
                proofRepository,
                orderRepository,
                deliveryAgentRepository,
                properties,
                passwordEncoder,
                smsSender,
                photoStorageService,
                securityUtils);
    }

    // ------------------------------------------------------------------
    // issueOtp
    // ------------------------------------------------------------------

    /**
     * The whole scheme rests on the rider never learning the code, so this asserts three things at
     * once: only the hash is persisted, the plaintext is what went out over SMS, and the response
     * carries no credential material.
     */
    @Test
    void issueOtp_persistsOnlyTheHashAndTextsThePlaintextToTheCustomer() {
        Order order = assignedOrder();
        stubAssignedAgent();
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(passwordEncoder.encode(anyString())).thenReturn(HASH);
        when(proofRepository.save(any(OrderDeliveryProof.class))).thenAnswer(i -> i.getArgument(0));

        DeliveryProofResponse response = service.issueOtp(ORDER_ID);

        ArgumentCaptor<OrderDeliveryProof> savedCaptor = ArgumentCaptor.forClass(OrderDeliveryProof.class);
        verify(proofRepository).save(savedCaptor.capture());
        OrderDeliveryProof saved = savedCaptor.getValue();
        assertEquals(HASH, saved.getOtpCodeHash());
        assertEquals(OrderDeliveryProof.ProofStatus.PENDING, saved.getStatus());
        assertEquals(0, saved.getOtpAttempts());
        assertNotNull(saved.getOtpExpiresAt());

        ArgumentCaptor<String> encoded = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(encoded.capture());
        String plaintext = encoded.getValue();
        assertEquals(6, plaintext.length());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(smsSender).send(eq("9998887776"), body.capture());
        assertTrue(body.getValue().contains(plaintext),
                "the customer's SMS is the only place the plaintext code may appear");

        assertNotNull(response.getOtpIssuedAt());
        assertEquals(properties.getMaxOtpAttempts(), response.getOtpAttemptsRemaining());
        assertFalse(response.getSatisfied());
    }

    /**
     * Reissue restores the full attempt allowance; a rider who burned every guess on a code the
     * customer never received must still be able to complete the handover.
     */
    @Test
    void issueOtp_reissueResetsTheAttemptCounter() {
        Order order = assignedOrder();
        OrderDeliveryProof existing = pendingProof(order);
        existing.setOtpAttempts(4);
        existing.setOtpIssuedAt(LocalDateTime.now().minusMinutes(5));

        stubAssignedAgent();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(existing));
        when(passwordEncoder.encode(anyString())).thenReturn(HASH);
        when(proofRepository.save(any(OrderDeliveryProof.class))).thenAnswer(i -> i.getArgument(0));

        DeliveryProofResponse response = service.issueOtp(ORDER_ID);

        assertEquals(0, existing.getOtpAttempts());
        assertEquals(properties.getMaxOtpAttempts(), response.getOtpAttemptsRemaining());
    }

    /** Without the cooldown a rider could hammer the endpoint and spam the customer with SMS. */
    @Test
    void issueOtp_rejectsResendInsideCooldown() {
        Order order = assignedOrder();
        OrderDeliveryProof existing = pendingProof(order);
        existing.setOtpIssuedAt(LocalDateTime.now().minusSeconds(10));

        stubCurrentAgentPrincipal();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.issueOtp(ORDER_ID));

        assertTrue(ex.getMessage().startsWith("Please wait "));
        verify(proofRepository, never()).save(any());
        verifyNoInteractions(smsSender);
    }

    @Test
    void issueOtp_rejectsWhenAlreadyVerified() {
        Order order = assignedOrder();
        OrderDeliveryProof verified = pendingProof(order);
        verified.setStatus(OrderDeliveryProof.ProofStatus.VERIFIED);

        stubCurrentAgentPrincipal();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(verified));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.issueOtp(ORDER_ID));

        assertEquals("Delivery proof is already verified for this order", ex.getMessage());
    }

    /** The kill switch is checked before anything is loaded, so nothing is touched when it is off. */
    @Test
    void issueOtp_rejectsWhenCaptureDisabled() {
        properties.setEnabled(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.issueOtp(ORDER_ID));

        assertEquals("Delivery proof capture is not enabled", ex.getMessage());
        verifyNoInteractions(orderRepository, proofRepository, smsSender, passwordEncoder);
    }

    /** A missing phone number must not void an already-persisted code. */
    @Test
    void issueOtp_skipsSmsWhenCustomerHasNoPhoneNumber() {
        Order order = assignedOrder();
        order.getCustomer().setPhoneNumber(null);

        stubAssignedAgent();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn(HASH);
        when(proofRepository.save(any(OrderDeliveryProof.class))).thenAnswer(i -> i.getArgument(0));

        DeliveryProofResponse response = service.issueOtp(ORDER_ID);

        assertNotNull(response.getOtpExpiresAt());
        verifyNoInteractions(smsSender);
    }

    // ------------------------------------------------------------------
    // verify
    // ------------------------------------------------------------------

    /**
     * A photo key upgrades the proof type and is validated against this feature's key prefix before
     * it is stored; the hash is cleared so a verified row holds no credential material.
     */
    @Test
    void verify_withPhoto_clearsHashAndRecordsCombinedProof() {
        Order order = assignedOrder();
        OrderDeliveryProof proof = issuedProof(order);
        String photoKey = "delivery-proofs/2026/08/5/abc.jpg";

        stubAssignedAgent();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(proof));
        when(passwordEncoder.matches("123456", HASH)).thenReturn(true);
        when(proofRepository.save(any(OrderDeliveryProof.class))).thenAnswer(i -> i.getArgument(0));
        when(photoStorageService.presignedUrl(photoKey)).thenReturn("https://signed.example/photo");

        DeliveryProofResponse response = service.verify(ORDER_ID, request("123456", photoKey));

        verify(photoStorageService).validateKey(photoKey);
        assertNull(proof.getOtpCodeHash(), "the hash must be discarded once the code is spent");
        assertEquals(OrderDeliveryProof.ProofStatus.VERIFIED, proof.getStatus());
        assertEquals(OrderDeliveryProof.ProofType.OTP_AND_PHOTO, proof.getProofType());
        assertEquals(photoKey, proof.getPhotoStorageKey());
        assertNotNull(proof.getPhotoUploadedAt());
        assertTrue(response.getPhotoAvailable());
        assertEquals("https://signed.example/photo", response.getPhotoUrl());
        assertTrue(response.getSatisfied());
    }

    @Test
    void verify_withoutPhoto_recordsOtpOnlyProof() {
        Order order = assignedOrder();
        OrderDeliveryProof proof = issuedProof(order);

        stubAssignedAgent();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(proof));
        when(passwordEncoder.matches("123456", HASH)).thenReturn(true);
        when(proofRepository.save(any(OrderDeliveryProof.class))).thenAnswer(i -> i.getArgument(0));

        DeliveryProofResponse response = service.verify(ORDER_ID, request("123456", null));

        assertEquals(OrderDeliveryProof.ProofType.OTP, proof.getProofType());
        assertFalse(response.getPhotoAvailable());
        assertNull(response.getPhotoUrl());
        verifyNoInteractions(photoStorageService);
    }

    /**
     * The increment must be persisted before the exception unwinds the transaction, otherwise the
     * rollback hands the rider unlimited guesses at a six-digit code.
     */
    @Test
    void verify_wrongCode_persistsTheAttemptBeforeRejecting() {
        Order order = assignedOrder();
        OrderDeliveryProof proof = issuedProof(order);

        stubCurrentAgentPrincipal();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(proof));
        when(passwordEncoder.matches("000000", HASH)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verify(ORDER_ID, request("000000", null)));

        assertEquals("Incorrect delivery proof code", ex.getMessage());
        verify(proofRepository).save(proof);
        assertEquals(1, proof.getOtpAttempts());
        assertEquals(OrderDeliveryProof.ProofStatus.PENDING, proof.getStatus());
    }

    /** Spending the last attempt closes the code out; only a fresh code reopens the handover. */
    @Test
    void verify_lastWrongAttempt_movesProofToFailed() {
        Order order = assignedOrder();
        OrderDeliveryProof proof = issuedProof(order);
        proof.setOtpAttempts(properties.getMaxOtpAttempts() - 1);

        stubCurrentAgentPrincipal();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(proof));
        when(passwordEncoder.matches("000000", HASH)).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.verify(ORDER_ID, request("000000", null)));

        assertEquals(properties.getMaxOtpAttempts(), proof.getOtpAttempts());
        assertEquals(OrderDeliveryProof.ProofStatus.FAILED, proof.getStatus());
    }

    @Test
    void verify_rejectsExhaustedAttemptsWithoutTouchingTheEncoder() {
        Order order = assignedOrder();
        OrderDeliveryProof proof = issuedProof(order);
        proof.setOtpAttempts(properties.getMaxOtpAttempts());

        stubCurrentAgentPrincipal();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(proof));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verify(ORDER_ID, request("123456", null)));

        assertEquals("Delivery proof attempts exhausted, request a new code", ex.getMessage());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void verify_rejectsExpiredCode() {
        Order order = assignedOrder();
        OrderDeliveryProof proof = issuedProof(order);
        proof.setOtpExpiresAt(LocalDateTime.now().minusMinutes(1));

        stubCurrentAgentPrincipal();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(proof));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verify(ORDER_ID, request("123456", null)));

        assertEquals("Delivery proof code has expired, request a new one", ex.getMessage());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void verify_rejectsWhenNoCodeWasEverIssued() {
        Order order = assignedOrder();

        stubCurrentAgentPrincipal();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verify(ORDER_ID, request("123456", null)));

        assertEquals("No delivery proof has been issued for this order", ex.getMessage());
    }

    /** The rider app retries on flaky networks, so a repeat submission returns state, not an error. */
    @Test
    void verify_alreadyVerified_returnsExistingStateIdempotently() {
        Order order = assignedOrder();
        OrderDeliveryProof proof = pendingProof(order);
        proof.setStatus(OrderDeliveryProof.ProofStatus.VERIFIED);
        proof.setVerifiedAt(LocalDateTime.now());

        stubCurrentAgentPrincipal();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(proof));

        DeliveryProofResponse response = service.verify(ORDER_ID, request("123456", null));

        assertTrue(response.getSatisfied());
        assertEquals("VERIFIED", response.getStatus());
        verify(proofRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder);
    }

    // ------------------------------------------------------------------
    // assignment checks
    // ------------------------------------------------------------------

    /**
     * {@code hasRole('DELIVERY_AGENT')} on the controller only proves the caller is <i>a</i> rider,
     * so the service re-checks the assignment. Without this any rider could close anyone's delivery.
     */
    @Test
    void verify_rejectsRiderWhoIsNotAssignedToTheOrder() {
        Order order = assignedOrder();
        DeliveryAgent someoneElse = new DeliveryAgent();
        someoneElse.setId(99L);
        order.setDeliveryAgent(someoneElse);

        stubCurrentAgentPrincipal();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> service.verify(ORDER_ID, request("123456", null)));

        assertEquals("Order is not assigned to you", ex.getMessage());
        verifyNoInteractions(proofRepository);
    }

    @Test
    void getForAgent_rejectsNonRiderPrincipal() {
        Order order = assignedOrder();
        Customer customer = new Customer();
        customer.setId(AGENT_ID);
        customer.setRole(User.UserRole.CUSTOMER);

        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUser()).thenReturn(customer);

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> service.getForAgent(ORDER_ID));

        assertEquals("Not a delivery agent account", ex.getMessage());
    }

    @Test
    void getForAgent_throwsWhenNoProofRowExists() {
        Order order = assignedOrder();

        stubCurrentAgentPrincipal();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.getForAgent(ORDER_ID));

        assertEquals("Delivery proof not found", ex.getMessage());
    }

    @Test
    void getForAgent_throwsWhenOrderIsUnknown() {
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.getForAgent(ORDER_ID));

        assertEquals("Order not found", ex.getMessage());
    }

    // ------------------------------------------------------------------
    // photo upload URL
    // ------------------------------------------------------------------

    /** The key is generated server-side so a rider cannot aim the upload at another order's prefix. */
    @Test
    void createPhotoUploadUrl_returnsServerGeneratedKey() {
        Order order = assignedOrder();
        String key = "delivery-proofs/2026/08/5/uuid.jpg";

        stubCurrentAgentPrincipal();
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(photoStorageService.buildKey(ORDER_ID, "image/jpeg")).thenReturn(key);
        when(photoStorageService.createUploadUrl(key, "image/jpeg")).thenReturn("https://upload.example/put");

        DeliveryProofService.PhotoUpload upload = service.createPhotoUploadUrl(ORDER_ID, "image/jpeg");

        assertEquals(key, upload.photoKey());
        assertEquals("https://upload.example/put", upload.uploadUrl());
        verifyNoInteractions(proofRepository);
    }

    @Test
    void createPhotoUploadUrl_rejectsWhenCaptureDisabled() {
        properties.setEnabled(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createPhotoUploadUrl(ORDER_ID, "image/jpeg"));

        assertEquals("Delivery proof capture is not enabled", ex.getMessage());
        verifyNoInteractions(photoStorageService, orderRepository);
    }

    // ------------------------------------------------------------------
    // enforcement gate
    // ------------------------------------------------------------------

    /** Availability and enforcement are separate switches; only the second one blocks a delivery. */
    @Test
    void assertProofSatisfied_isInertWhileEnforcementIsOff() {
        Order order = assignedOrder();

        properties.setEnforced(false);
        service.assertProofSatisfied(order);

        properties.setEnabled(false);
        properties.setEnforced(true);
        service.assertProofSatisfied(order);

        verifyNoInteractions(proofRepository);
    }

    @Test
    void assertProofSatisfied_blocksDeliveryWhenProofIsMissing() {
        Order order = assignedOrder();
        properties.setEnforced(true);
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.assertProofSatisfied(order));

        assertEquals("Delivery proof must be verified before completing delivery", ex.getMessage());
    }

    @Test
    void assertProofSatisfied_blocksDeliveryWhenProofIsStillPending() {
        Order order = assignedOrder();
        properties.setEnforced(true);
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(pendingProof(order)));

        assertThrows(BusinessException.class, () -> service.assertProofSatisfied(order));
    }

    @Test
    void assertProofSatisfied_passesForVerifiedProof() {
        Order order = assignedOrder();
        OrderDeliveryProof proof = pendingProof(order);
        proof.setStatus(OrderDeliveryProof.ProofStatus.VERIFIED);

        properties.setEnforced(true);
        when(proofRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(proof));

        service.assertProofSatisfied(order);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** Principal resolution only, for paths that never load the {@link DeliveryAgent} row. */
    private void stubCurrentAgentPrincipal() {
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(AGENT_ID);
        agent.setRole(User.UserRole.DELIVERY_AGENT);
        when(securityUtils.getCurrentUser()).thenReturn(agent);
    }

    /** Principal resolution plus the repository lookup used when a proof row is written. */
    private void stubAssignedAgent() {
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(AGENT_ID);
        agent.setRole(User.UserRole.DELIVERY_AGENT);
        when(securityUtils.getCurrentUser()).thenReturn(agent);
        when(deliveryAgentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
    }

    private Order assignedOrder() {
        Customer customer = new Customer();
        customer.setId(1L);
        customer.setPhoneNumber("9998887776");

        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(AGENT_ID);

        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNumber("BHK-1001");
        order.setStatus(Order.OrderStatus.OUT_FOR_DELIVERY);
        order.setCustomer(customer);
        order.setDeliveryAgent(agent);
        return order;
    }

    /** A proof row with no live code — the state right after creation. */
    private OrderDeliveryProof pendingProof(Order order) {
        OrderDeliveryProof proof = new OrderDeliveryProof();
        proof.setId(11L);
        proof.setOrder(order);
        proof.setProofType(OrderDeliveryProof.ProofType.OTP);
        proof.setStatus(OrderDeliveryProof.ProofStatus.PENDING);
        proof.setOtpAttempts(0);
        return proof;
    }

    /** A proof row carrying a live, unexpired code. */
    private OrderDeliveryProof issuedProof(Order order) {
        OrderDeliveryProof proof = pendingProof(order);
        proof.setOtpCodeHash(HASH);
        proof.setOtpIssuedAt(LocalDateTime.now().minusMinutes(1));
        proof.setOtpExpiresAt(LocalDateTime.now().plusMinutes(properties.getOtpExpiryMinutes()));
        return proof;
    }

    private DeliveryProofVerifyRequest request(String code, String photoKey) {
        DeliveryProofVerifyRequest request = new DeliveryProofVerifyRequest();
        request.setOtpCode(code);
        request.setPhotoKey(photoKey);
        request.setRecipientName("Asha");
        request.setCaptureLatitude(12.97);
        request.setCaptureLongitude(77.59);
        request.setNotes("Handed at the gate");
        return request;
    }
}
