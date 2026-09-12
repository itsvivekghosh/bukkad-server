package com.bhukkad.order.domain.service.impl;

import com.bhukkad.order.domain.entity.OrderEtaSnapshot;
import com.bhukkad.order.domain.repository.OrderEtaSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records point-in-time ETA predictions for accuracy tracking (Batch B depth).
 */
@Service
@RequiredArgsConstructor
public class OrderEtaService {

    private final OrderEtaSnapshotRepository repository;

    @Transactional
    public OrderEtaSnapshot record(Long orderId, int etaMinutes) {
        OrderEtaSnapshot snapshot = new OrderEtaSnapshot();
        snapshot.setOrderId(orderId);
        snapshot.setEtaMinutes(etaMinutes);
        return repository.save(snapshot);
    }

    @Transactional
    public void markActual(Long orderId, int actualMinutes) {
        repository.findByOrderId(orderId).stream()
                .reduce((first, second) -> second)
                .ifPresent(s -> {
                    s.setActualMinutes(actualMinutes);
                    repository.save(s);
                });
    }
}
