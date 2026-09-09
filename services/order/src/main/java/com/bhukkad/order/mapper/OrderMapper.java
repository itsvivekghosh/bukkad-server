package com.bhukkad.order.mapper;

import com.bhukkad.order.api.OrderItemResponse;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface OrderMapper {
    OrderMapper INSTANCE = Mappers.getMapper(OrderMapper.class);

    OrderItemResponse toItemResponse(OrderItem orderItem);
}
