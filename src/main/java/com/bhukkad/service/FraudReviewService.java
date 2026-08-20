package com.bhukkad.service;

import com.bhukkad.dto.request.FraudReviewActionRequest;
import com.bhukkad.dto.response.FraudReviewActionResponse;
import com.bhukkad.entity.FraudEvent;
import com.bhukkad.entity.FraudReviewAction;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.FraudEventRepository;
import com.bhukkad.repository.FraudReviewActionRepository;
import com.bhukkad.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Manual fraud review queue: an admin decides what to do with a suspicious fraud
 * event (Fraud Dashboard). Blocking a customer deactivates their account; the
 * decision is recorded so events are never double-reviewed.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FraudReviewService {

    private final FraudReviewActionRepository fraudReviewActionRepository;
    private final FraudEventRepository fraudEventRepository;
    private final UserRepository userRepository;

    public List<FraudReviewActionResponse> listPending() {
        return fraudReviewActionRepository
                .findByStatusOrderByCreatedAtDesc(FraudReviewAction.FraudReviewStatus.PENDING)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public FraudReviewActionResponse action(Long fraudEventId, FraudReviewActionRequest request, Long adminId) {
        FraudEvent event = fraudEventRepository.findById(fraudEventId)
                .orElseThrow(() -> new ResourceNotFoundException("Fraud event not found"));

        if (fraudReviewActionRepository.existsByFraudEventId(fraudEventId)) {
            throw new BusinessException("Fraud event already reviewed");
        }

        FraudReviewAction.FraudReviewActionType actionType;
        try {
            actionType = FraudReviewAction.FraudReviewActionType.valueOf(request.getAction().trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("Invalid review action: " + request.getAction());
        }

        User reviewer = userRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Reviewer not found"));

        FraudReviewAction action = new FraudReviewAction();
        action.setFraudEvent(event);
        action.setCustomer(event.getCustomer());
        action.setAction(actionType);
        action.setNotes(request.getNotes());
        action.setReviewedBy(reviewer);
        action.setReviewedAt(LocalDateTime.now());
        action.setStatus(FraudReviewAction.FraudReviewStatus.DONE);

        if (actionType == FraudReviewAction.FraudReviewActionType.BLOCK_CUSTOMER && event.getCustomer() != null) {
            User customerUser = userRepository.findById(event.getCustomer().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
            customerUser.setActive(false);
            userRepository.save(customerUser);
        }

        return toResponse(fraudReviewActionRepository.save(action));
    }

    private FraudReviewActionResponse toResponse(FraudReviewAction action) {
        FraudEvent event = action.getFraudEvent();
        return FraudReviewActionResponse.builder()
                .id(action.getId())
                .fraudEventId(event.getId())
                .eventType(event.getEventType())
                .customerId(action.getCustomer() != null ? action.getCustomer().getId() : null)
                .action(action.getAction().name())
                .status(action.getStatus().name())
                .notes(action.getNotes())
                .reviewedBy(action.getReviewedBy() != null ? action.getReviewedBy().getId() : null)
                .reviewedAt(action.getReviewedAt() != null ? action.getReviewedAt().toString() : null)
                .createdAt(action.getCreatedAt() != null ? action.getCreatedAt().toString() : null)
                .build();
    }
}
