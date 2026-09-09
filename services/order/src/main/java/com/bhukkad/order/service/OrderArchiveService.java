package com.bhukkad.order.service;

import com.bhukkad.order.domain.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Archives old orders into the partitioned {@code orders_archive} table and
 * removes them from the hot {@code orders} table (Batch B depth). The native
 * SQL is the PostgreSQL port of the monolith's MySQL
 * {@code INSERT ... SELECT ... LIMIT} archive job.
 */
@Service
@RequiredArgsConstructor
public class OrderArchiveService {

    private final OrderRepository orderRepository;

    @Transactional
    public int archiveBefore(LocalDate date, int limit) {
        return orderRepository.archiveOldOrders(date, limit);
    }
}