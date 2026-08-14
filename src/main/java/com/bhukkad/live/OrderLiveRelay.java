package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;

public interface OrderLiveRelay {

    void publish(OrderLiveUpdate update);
}
