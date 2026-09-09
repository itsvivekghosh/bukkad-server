package com.bhukkad.delivery.api;

import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DefaultEtaPort implements EtaPort {

    @Override
    public Optional<EtaSnapshot> computeEta(Long orderId) {
        return Optional.empty();
    }
}
