package com.bhukkad.delivery.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.delivery.domain.DeliveryAgent;
import com.bhukkad.delivery.domain.DeliveryAgentRepository;
import com.bhukkad.delivery.domain.DeliveryAssignment;
import com.bhukkad.delivery.domain.DeliveryAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final DeliveryAgentRepository agentRepository;
    private final DeliveryAssignmentRepository assignmentRepository;
    private final DeliveryEventPublisher eventPublisher;

    @Transactional
    public DeliveryAssignment assign(Long orderId) {
        if (assignmentRepository.findByOrderId(orderId).isPresent()) {
            throw new BusinessException("Order already assigned: " + orderId);
        }
        DeliveryAgent agent = agentRepository.findFirstByIsActiveTrue()
                .orElseThrow(() -> new BusinessException("No active delivery agent available"));
        DeliveryAssignment assignment = new DeliveryAssignment();
        assignment.setOrderId(orderId);
        assignment.setAgentId(agent.getId());
        assignment.setStatus(DeliveryAssignment.STATUS_ASSIGNED);
        assignment.setAssignedAt(LocalDateTime.now());
        assignment = assignmentRepository.save(assignment);

        eventPublisher.deliveryAssigned(orderId, agent.getId());
        return assignment;
    }

    @Transactional
    public DeliveryAssignment markDelivered(Long orderId) {
        DeliveryAssignment assignment = assignmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found: " + orderId));
        if (DeliveryAssignment.STATUS_DELIVERED.equals(assignment.getStatus())) {
            throw new BusinessException("Already delivered: " + orderId);
        }
        assignment.setStatus(DeliveryAssignment.STATUS_DELIVERED);
        assignment.setDeliveredAt(LocalDateTime.now());
        assignmentRepository.save(assignment);

        eventPublisher.orderDelivered(orderId, assignment.getAgentId());
        return assignment;
    }
}