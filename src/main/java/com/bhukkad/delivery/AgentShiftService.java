package com.bhukkad.delivery;

import com.bhukkad.entity.AgentShift;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.repository.AgentShiftRepository;
import com.bhukkad.repository.DeliveryAgentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/** Rider shift lifecycle: start / end / query. */
@Service
@RequiredArgsConstructor
public class AgentShiftService {

    private static final int DEFAULT_LIST_LIMIT = 20;

    private final AgentShiftRepository agentShiftRepository;
    private final DeliveryAgentRepository deliveryAgentRepository;

    @Transactional
    public AgentShift startShift(Long agentId) {
        if (agentShiftRepository.findActiveShiftByAgentId(agentId).isPresent()) {
            throw new BusinessException("Agent already has an active shift");
        }
        DeliveryAgent agent = deliveryAgentRepository.findById(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery agent not found"));
        AgentShift shift = new AgentShift();
        shift.setAgent(agent);
        shift.setShiftDate(LocalDate.now());
        LocalTime now = LocalTime.now();
        shift.setStartTime(now);
        shift.setEndTime(now);
        shift.setStatus(AgentShift.ShiftStatus.ACTIVE);
        return agentShiftRepository.save(shift);
    }

    @Transactional
    public AgentShift endShift(Long agentId) {
        AgentShift shift = agentShiftRepository.findActiveShiftByAgentId(agentId)
                .orElseThrow(() -> new BusinessException("No active shift to end"));
        shift.setStatus(AgentShift.ShiftStatus.COMPLETED);
        shift.setEndTime(LocalTime.now());
        return agentShiftRepository.save(shift);
    }

    @Transactional(readOnly = true)
    public Optional<AgentShift> getCurrentShift(Long agentId) {
        return agentShiftRepository.findActiveShiftByAgentId(agentId);
    }

    @Transactional(readOnly = true)
    public List<AgentShift> listShifts(Long agentId, Integer limit) {
        int size = (limit == null || limit <= 0) ? DEFAULT_LIST_LIMIT : limit;
        return agentShiftRepository.findByAgentIdOrderByShiftDateDesc(agentId, PageRequest.of(0, size));
    }
}