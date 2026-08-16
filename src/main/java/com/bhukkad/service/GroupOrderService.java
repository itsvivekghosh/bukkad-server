package com.bhukkad.service;

import com.bhukkad.dto.request.GroupOrderRequest;
import com.bhukkad.dto.response.GroupOrderResponse;
import com.bhukkad.entity.GroupOrder;
import com.bhukkad.entity.GroupOrder.GroupOrderStatus;
import java.util.List;

public interface GroupOrderService {
    GroupOrderResponse createGroupOrder(GroupOrderRequest request);
    GroupOrderResponse addItemToGroupOrder(Long groupOrderId, Long cartId, Long customerId);
    GroupOrderResponse checkoutGroupOrder(Long groupOrderId, String paymentMethod, String idempotencyKey);
    GroupOrderResponse getGroupOrderById(Long id);
    GroupOrderResponse getActiveGroupOrderByCustomer(Long customerId);
    List<GroupOrderResponse> getGroupOrdersByRestaurant(Long restaurantId, GroupOrderStatus status);
    void cancelGroupOrder(Long groupOrderId, String reason);
}