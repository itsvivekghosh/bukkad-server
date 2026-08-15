package com.bhukkad.delivery;

import com.bhukkad.dto.request.DeliveryProofVerifyRequest;
import com.bhukkad.dto.response.DeliveryProofResponse;
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
import com.bhukkad.util.OTPGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Issues and verifies proof of delivery for an order handover.
 *
 * <h2>Why this exists</h2>
 * <p>Before this service a rider could mark any assigned order {@code DELIVERED} from anywhere,
 * which made "item never arrived" disputes unresolvable: there was no evidence separating a genuine
 * failed delivery from a rider who pocketed the order. A one-time code known only to the customer
 * proves the two parties actually met, and an optional photo covers contactless drops where no code
 * can be read out.</p>
 *
 * <h2>The OTP never leaves the customer's phone</h2>
 * <p>Only a BCrypt hash is persisted in {@code otp_code_hash}. The plaintext exists in memory for
 * the duration of {@link #issueOtp(Long)} and is written to exactly one place — an SMS to
 * {@code order.customer.phoneNumber}. It is not returned by any method here, not logged, and not
 * present on {@link DeliveryProofResponse}. Handing the code to the rider in an API response would
 * make the whole check theatre, since the rider could then close the order without meeting anyone.
 * On successful verification the hash is cleared, so a verified row carries no credential material
 * at all.</p>
 *
 * <h2>Attempt and expiry limits</h2>
 * <p>A 6-digit code is guessable in 10<sup>6</sup> tries, so verification is bounded twice: by
 * {@link DeliveryProofProperties#getMaxOtpAttempts()} and by
 * {@link DeliveryProofProperties#getOtpExpiryMinutes()}. Exhausting the attempts moves the row to
 * {@link OrderDeliveryProof.ProofStatus#FAILED}, which is terminal for that code — the rider must
 * request a fresh one, which resets the counter. Reissue is throttled by
 * {@link DeliveryProofProperties#getOtpResendCooldownSeconds()} so a rider cannot use repeated
 * reissues to spam the customer with SMS.</p>
 *
 * <h2>Enforcement is a separate switch from availability</h2>
 * <p>{@code enabled} controls whether proof can be captured; {@code enforced} controls whether a
 * missing proof blocks {@code markOrderDelivered}. They are deliberately independent so the flow can
 * be rolled out and observed in production before it becomes load-bearing. See
 * {@link #assertProofSatisfied(Order)}.</p>
 *
 * <h2>Failure posture</h2>
 * <p>Unlike fraud enforcement, this service <b>fails closed</b> on verification: a mismatched,
 * expired or exhausted code throws and the order stays undelivered. It fails open only on SMS
 * dispatch, where a carrier outage is logged and the code remains valid so the rider can retry
 * delivery of the message rather than being forced to abandon the drop.</p>
 */
@Service
@RequiredArgsConstructor
public class DeliveryProofService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryProofService.class);

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final OrderDeliveryProofRepository proofRepository;
    private final OrderRepository orderRepository;
    private final DeliveryAgentRepository deliveryAgentRepository;
    private final DeliveryProofProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final SmsSender smsSender;
    private final DeliveryProofPhotoStorageService photoStorageService;
    private final SecurityUtils securityUtils;

    /**
     * Generates a fresh handover code for the order and texts it to the customer.
     *
     * <p>Idempotent in the sense that it reuses the existing proof row rather than creating a
     * second one — {@code order_delivery_proofs} has a unique constraint on {@code order_id} and a
     * second insert would fail. Calling this again replaces the previous code, which is the intended
     * recovery path when the customer did not receive the SMS.</p>
     *
     * @param orderId order being handed over
     * @return the proof state, without the code
     * @throws BusinessException     if proof capture is disabled, the order is already verified, or
     *                               the resend cooldown has not elapsed
     * @throws UnauthorizedException if the caller is not the agent assigned to this order
     */
    @Transactional(readOnly = false)
    public DeliveryProofResponse issueOtp(Long orderId) {
        if (!properties.isEnabled()) {
            throw new BusinessException("Delivery proof capture is not enabled");
        }

        Order order = loadOrderForAssignedAgent(orderId);
        OrderDeliveryProof proof = proofRepository.findByOrderId(orderId)
                .orElseGet(() -> newProofFor(order));

        if (proof.getStatus() == OrderDeliveryProof.ProofStatus.VERIFIED) {
            throw new BusinessException("Delivery proof is already verified for this order");
        }

        LocalDateTime now = LocalDateTime.now();
        assertResendCooldownElapsed(proof, now);

        String code = OTPGenerator.generateOTP();
        proof.setOtpCodeHash(passwordEncoder.encode(code));
        proof.setOtpIssuedAt(now);
        proof.setOtpExpiresAt(now.plusMinutes(properties.getOtpExpiryMinutes()));
        // A new code restores the full allowance; otherwise a rider who burned all attempts on a
        // code the customer never received could never complete the delivery.
        proof.setOtpAttempts(0);
        proof.setStatus(OrderDeliveryProof.ProofStatus.PENDING);
        proof.setAgent(resolveCurrentAgent());

        OrderDeliveryProof saved = proofRepository.save(proof);
        sendOtpSms(order, code);

        log.info("DELIVERY_PROOF_OTP_ISSUED | orderId={} | proofId={} | expiresAt={}",
                orderId, saved.getId(), saved.getOtpExpiresAt());
        return toResponse(saved, order);
    }

    /**
     * Checks the code the customer read out to the rider and, on a match, finalises the proof.
     *
     * <p>Every outcome is persisted. A wrong code increments {@code otpAttempts} and that increment
     * survives the thrown exception because the counter is saved before the throw — otherwise the
     * rollback would hand the rider unlimited guesses.</p>
     *
     * @param orderId order being handed over
     * @param request the code plus optional photo key, recipient name, capture coordinates and notes
     * @return the verified proof state
     * @throws BusinessException     if no code was issued, the code expired, attempts are exhausted,
     *                               or the code does not match
     * @throws UnauthorizedException if the caller is not the agent assigned to this order
     */
    @Transactional(readOnly = false, noRollbackFor = BusinessException.class)
    public DeliveryProofResponse verify(Long orderId, DeliveryProofVerifyRequest request) {
        if (!properties.isEnabled()) {
            throw new BusinessException("Delivery proof capture is not enabled");
        }

        Order order = loadOrderForAssignedAgent(orderId);
        OrderDeliveryProof proof = proofRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException("No delivery proof has been issued for this order"));

        if (proof.getStatus() == OrderDeliveryProof.ProofStatus.VERIFIED) {
            // Re-submitting an accepted code is harmless and the rider app may retry on a flaky
            // network, so return the existing state instead of failing the handover.
            return toResponse(proof, order);
        }
        if (!StringUtils.hasText(proof.getOtpCodeHash())) {
            throw new BusinessException("No delivery proof has been issued for this order");
        }
        if (proof.getOtpExpiresAt() != null && proof.getOtpExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("Delivery proof code has expired, request a new one");
        }
        if (attemptsUsed(proof) >= properties.getMaxOtpAttempts()) {
            throw new BusinessException("Delivery proof attempts exhausted, request a new code");
        }

        if (!passwordEncoder.matches(request.getOtpCode(), proof.getOtpCodeHash())) {
            recordFailedAttempt(orderId, proof);
            throw new BusinessException("Incorrect delivery proof code");
        }

        applyCaptureDetails(proof, request);
        proof.setOtpCodeHash(null);
        proof.setVerifiedAt(LocalDateTime.now());
        proof.setStatus(OrderDeliveryProof.ProofStatus.VERIFIED);
        proof.setAgent(resolveCurrentAgent());
        proof.setProofType(StringUtils.hasText(request.getPhotoKey())
                ? OrderDeliveryProof.ProofType.OTP_AND_PHOTO
                : OrderDeliveryProof.ProofType.OTP);

        OrderDeliveryProof saved = proofRepository.save(proof);
        log.info("DELIVERY_PROOF_VERIFIED | orderId={} | proofId={} | type={} | photo={}",
                orderId, saved.getId(), saved.getProofType(), saved.getPhotoStorageKey() != null);
        return toResponse(saved, order);
    }

    /**
     * Issues a presigned {@code PUT} URL the rider app uploads the handover photo to.
     *
     * <p>The bytes go straight from the device to object storage; the server only ever sees the key.
     * The returned key must be echoed back in {@link #verify(Long, DeliveryProofVerifyRequest)} for
     * the photo to be attached to the proof.</p>
     *
     * @param orderId     order being handed over
     * @param contentType image MIME type, validated against the storage allowlist
     * @return the upload URL and the key to submit with verification
     * @throws BusinessException     if photo storage is not configured or the type is unsupported
     * @throws UnauthorizedException if the caller is not the agent assigned to this order
     */
    public PhotoUpload createPhotoUploadUrl(Long orderId, String contentType) {
        if (!properties.isEnabled()) {
            throw new BusinessException("Delivery proof capture is not enabled");
        }
        loadOrderForAssignedAgent(orderId);

        String photoKey = photoStorageService.buildKey(orderId, contentType);
        String uploadUrl = photoStorageService.createUploadUrl(photoKey, contentType);
        log.info("DELIVERY_PROOF_PHOTO_URL_ISSUED | orderId={} | key={}", orderId, photoKey);
        return new PhotoUpload(uploadUrl, photoKey);
    }

    /**
     * Returns the current proof state for the assigned rider, including a short-lived photo URL.
     *
     * @param orderId order being handed over
     * @return the proof state
     * @throws ResourceNotFoundException if no proof has been created for the order
     * @throws UnauthorizedException     if the caller is not the agent assigned to this order
     */
    public DeliveryProofResponse getForAgent(Long orderId) {
        Order order = loadOrderForAssignedAgent(orderId);
        OrderDeliveryProof proof = proofRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery proof not found"));
        return toResponse(proof, order);
    }

    /**
     * Gate called from the delivery transition before the status is mutated.
     *
     * <p>Silent when {@code enforced} is false: the outcome is still recorded by the rest of this
     * service, so operations can measure how often riders actually capture proof before the check
     * becomes mandatory. Flipping {@code enforced} to true before the rider app ships would strand
     * every in-flight delivery, since no client would be able to satisfy it.</p>
     *
     * @param order order about to be marked delivered
     * @throws BusinessException if enforcement is on and no satisfied proof exists
     */
    public void assertProofSatisfied(Order order) {
        if (!properties.isEnabled() || !properties.isEnforced()) {
            return;
        }
        boolean satisfied = proofRepository.findByOrderId(order.getId())
                .map(OrderDeliveryProof::isSatisfied)
                .orElse(false);
        if (!satisfied) {
            log.warn("DELIVERY_PROOF_MISSING_BLOCKED | orderId={}", order.getId());
            throw new BusinessException("Delivery proof must be verified before completing delivery");
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * Loads the order with its associations and asserts the caller is the assigned rider.
     *
     * <p>Ownership is re-checked here rather than trusted from the controller's role annotation:
     * {@code hasRole('DELIVERY_AGENT')} only proves the caller is <i>a</i> rider, not the rider for
     * this order, so without this check any rider could read or complete anyone's delivery.</p>
     */
    private Order loadOrderForAssignedAgent(Long orderId) {
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        Long currentUserId = resolveCurrentAgentId();
        if (order.getDeliveryAgent() == null
                || !order.getDeliveryAgent().getId().equals(currentUserId)) {
            throw new UnauthorizedException("Order is not assigned to you");
        }
        return order;
    }

    private Long resolveCurrentAgentId() {
        User user = securityUtils.getCurrentUser();
        if (user.getRole() != User.UserRole.DELIVERY_AGENT) {
            throw new UnauthorizedException("Not a delivery agent account");
        }
        return user.getId();
    }

    /**
     * Resolves the {@link DeliveryAgent} row for the caller. {@code DeliveryAgent} extends
     * {@code User} in the same table hierarchy, so the id is shared and a direct lookup is enough.
     */
    private DeliveryAgent resolveCurrentAgent() {
        return deliveryAgentRepository.findById(resolveCurrentAgentId())
                .orElseThrow(() -> new ResourceNotFoundException("Delivery agent not found"));
    }

    private OrderDeliveryProof newProofFor(Order order) {
        OrderDeliveryProof proof = new OrderDeliveryProof();
        proof.setOrder(order);
        proof.setProofType(OrderDeliveryProof.ProofType.OTP);
        proof.setStatus(OrderDeliveryProof.ProofStatus.PENDING);
        proof.setOtpAttempts(0);
        return proof;
    }

    private void assertResendCooldownElapsed(OrderDeliveryProof proof, LocalDateTime now) {
        LocalDateTime issuedAt = proof.getOtpIssuedAt();
        if (issuedAt == null) {
            return;
        }
        long elapsed = Duration.between(issuedAt, now).getSeconds();
        long cooldown = properties.getOtpResendCooldownSeconds();
        if (elapsed < cooldown) {
            throw new BusinessException("Please wait " + (cooldown - elapsed)
                    + " seconds before requesting another delivery proof code");
        }
    }

    /**
     * Sends the plaintext code to the customer. Swallows dispatch failures on purpose: the code is
     * already persisted and still valid, so a carrier outage should not void the handover — the
     * rider can request a resend once the cooldown elapses.
     */
    private void sendOtpSms(Order order, String code) {
        String phoneNumber = order.getCustomer() != null ? order.getCustomer().getPhoneNumber() : null;
        if (!StringUtils.hasText(phoneNumber)) {
            log.warn("DELIVERY_PROOF_OTP_SMS_SKIPPED | orderId={} | reason=no_phone", order.getId());
            return;
        }
        try {
            smsSender.send(phoneNumber, "Your Bhukkad delivery code for order "
                    + order.getOrderNumber() + " is " + code
                    + ". Share it only with your delivery partner. Valid for "
                    + properties.getOtpExpiryMinutes() + " minutes.");
        } catch (Exception ex) {
            log.error("DELIVERY_PROOF_OTP_SMS_FAILED | orderId={} | error={}",
                    order.getId(), ex.getMessage());
        }
    }

    /**
     * Persists the failed guess and closes the code out once the allowance is spent. Saved before
     * the caller throws so the increment is not rolled back with the rejected request.
     */
    private void recordFailedAttempt(Long orderId, OrderDeliveryProof proof) {
        int attempts = attemptsUsed(proof) + 1;
        proof.setOtpAttempts(attempts);
        if (attempts >= properties.getMaxOtpAttempts()) {
            proof.setStatus(OrderDeliveryProof.ProofStatus.FAILED);
        }
        proofRepository.save(proof);
        log.warn("DELIVERY_PROOF_OTP_MISMATCH | orderId={} | attempts={} | max={}",
                orderId, attempts, properties.getMaxOtpAttempts());
    }

    /**
     * Copies the optional capture metadata across. The photo key is untrusted client input, so it is
     * validated against this feature's own key prefix before being stored — otherwise a rider could
     * submit an invoice or menu-image key and have the dashboard presign someone else's object.
     */
    private void applyCaptureDetails(OrderDeliveryProof proof, DeliveryProofVerifyRequest request) {
        if (StringUtils.hasText(request.getPhotoKey())) {
            photoStorageService.validateKey(request.getPhotoKey());
            proof.setPhotoStorageKey(request.getPhotoKey());
            proof.setPhotoUploadedAt(LocalDateTime.now());
        }
        if (StringUtils.hasText(request.getRecipientName())) {
            proof.setRecipientName(request.getRecipientName());
        }
        if (request.getCaptureLatitude() != null) {
            proof.setCaptureLatitude(request.getCaptureLatitude());
        }
        if (request.getCaptureLongitude() != null) {
            proof.setCaptureLongitude(request.getCaptureLongitude());
        }
        if (StringUtils.hasText(request.getNotes())) {
            proof.setNotes(request.getNotes());
        }
    }

    private int attemptsUsed(OrderDeliveryProof proof) {
        return proof.getOtpAttempts() == null ? 0 : proof.getOtpAttempts();
    }

    /**
     * Maps to the wire shape. Exposes {@code otpAttemptsRemaining} rather than the raw counter so
     * the rider app can warn before the last try, and never exposes the code or its hash.
     */
    private DeliveryProofResponse toResponse(OrderDeliveryProof proof, Order order) {
        int remaining = Math.max(0, properties.getMaxOtpAttempts() - attemptsUsed(proof));
        String photoKey = proof.getPhotoStorageKey();
        return DeliveryProofResponse.builder()
                .id(proof.getId())
                .orderId(order != null ? order.getId() : null)
                .orderNumber(order != null ? order.getOrderNumber() : null)
                .proofType(proof.getProofType() != null ? proof.getProofType().name() : null)
                .status(proof.getStatus() != null ? proof.getStatus().name() : null)
                .otpIssuedAt(format(proof.getOtpIssuedAt()))
                .otpExpiresAt(format(proof.getOtpExpiresAt()))
                .otpAttemptsRemaining(remaining)
                .verifiedAt(format(proof.getVerifiedAt()))
                .photoAvailable(StringUtils.hasText(photoKey))
                .photoUrl(StringUtils.hasText(photoKey) ? photoStorageService.presignedUrl(photoKey) : null)
                .recipientName(proof.getRecipientName())
                .notes(proof.getNotes())
                .satisfied(proof.isSatisfied())
                .enforced(properties.isEnforced())
                .createdAt(format(proof.getCreatedAt()))
                .build();
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(TIMESTAMP);
    }

    /**
     * Presigned upload handle returned to the rider app: where to {@code PUT} the photo, and the key
     * to submit alongside the OTP so the stored object is linked to the proof.
     *
     * @param uploadUrl short-lived presigned {@code PUT} URL
     * @param photoKey  storage key to echo back on verification
     */
    public record PhotoUpload(String uploadUrl, String photoKey) {
    }
}
