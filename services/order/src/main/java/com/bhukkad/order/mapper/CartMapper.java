package com.bhukkad.order.mapper;

import com.bhukkad.order.api.CartItemResponse;
import com.bhukkad.order.domain.Cart;
import com.bhukkad.order.domain.CartItem;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface CartMapper {
    CartMapper INSTANCE = Mappers.getMapper(CartMapper.class);

    CartItemResponse toItemResponse(CartItem cartItem);

    com.bhukkad.order.api.CartResponse toResponse(Cart cart);
}
