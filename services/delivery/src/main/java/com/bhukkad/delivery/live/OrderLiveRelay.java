package com.bhukkad.delivery.live;

import com.bhukkad.delivery.dto.response.OrderLiveUpdate;

public interface OrderLiveRelay {

    void publish(OrderLiveUpdate update);
}
