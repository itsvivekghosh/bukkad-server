package com.bhukkad.order.domain.mapper;

import com.bhukkad.order.api.dto.response.OrderItemResponse;
import com.bhukkad.order.domain.entity.Order;
import com.bhukkad.order.domain.entity.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface OrderMapper {
    OrderMapper INSTANCE = Mappers.getMapper(OrderMapper.class);

    OrderItemResponse toItemResponse(OrderItem orderItem);
}
