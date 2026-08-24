package com.bhukkad.repository;

import com.bhukkad.entity.AgentShift;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentShiftRepository extends JpaRepository<AgentShift, Long> {

    Optional<AgentShift> findByAgentIdAndShiftDate(Long agentId, LocalDate shiftDate);

    List<AgentShift> findByAgentIdOrderByShiftDateDesc(Long agentId, Pageable pageable);

    @Query("SELECT s FROM AgentShift s WHERE s.agent.id = :agentId AND s.status = 'ACTIVE' " +
            "ORDER BY s.shiftDate DESC, s.id DESC")
    Optional<AgentShift> findActiveShiftByAgentId(@Param("agentId") Long agentId);
}
