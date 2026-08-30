package com.bhukkad.delivery;

import com.bhukkad.delivery.api.EtaPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Adapter exposing the delivery domain's ETA engine to other domains through
 * the {@link EtaPort} contract.
 */
@Service
@RequiredArgsConstructor
public class EtaPortAdapter implements EtaPort {

    private final OrderEtaService orderEtaService;

    @Override
    public Optional<EtaSnapshot> computeEta(Long orderId) {
        return orderEtaService.computeLiveEtaForOrder(orderId);
    }
}
