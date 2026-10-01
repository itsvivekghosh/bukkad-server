package com.bhukkad.commerce.delivery.infrastructure.messaging;

import com.bhukkad.commerce.delivery.api.dto.response.OrderLiveUpdate;

public interface OrderLiveRelay {

    void publish(OrderLiveUpdate update);
}
