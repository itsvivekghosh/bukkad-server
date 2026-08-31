package com.bhukkad.delivery.api;

import com.bhukkad.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/deliveries")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;

    @PostMapping("/orders/{orderId}/assign")
    public Object assign(@PathVariable Long orderId) {
        return deliveryService.assign(orderId);
    }

    @PostMapping("/orders/{orderId}/delivered")
    public Object markDelivered(@PathVariable Long orderId) {
        return deliveryService.markDelivered(orderId);
    }
}