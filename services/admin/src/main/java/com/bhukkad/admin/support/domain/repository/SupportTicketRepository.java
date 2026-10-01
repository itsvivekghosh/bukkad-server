package com.bhukkad.admin.support.domain.repository;

import com.bhukkad.admin.support.domain.entity.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    List<SupportTicket> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
}