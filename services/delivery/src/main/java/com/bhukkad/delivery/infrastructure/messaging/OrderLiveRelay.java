package com.bhukkad.delivery.infrastructure.messaging;

import com.bhukkad.delivery.api.dto.response.OrderLiveUpdate;

public interface OrderLiveRelay {

    void publish(OrderLiveUpdate update);
}
