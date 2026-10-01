package com.bhukkad.commerce.order.domain.mapper;

import com.bhukkad.commerce.order.api.dto.response.CartItemResponse;
import com.bhukkad.commerce.order.domain.entity.Cart;
import com.bhukkad.commerce.order.domain.entity.CartItem;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import com.bhukkad.commerce.order.api.dto.response.CartResponse;

@Mapper(componentModel = "spring")
public interface CartMapper {
    CartMapper INSTANCE = Mappers.getMapper(CartMapper.class);

    CartItemResponse toItemResponse(CartItem cartItem);

    CartResponse toResponse(Cart cart);
}
