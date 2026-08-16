package com.bhukkad.mapper;

import com.bhukkad.dto.response.OrderItemResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderItem;
import com.bhukkad.entity.Payment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.ArrayList;
import java.util.List;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    @Mapping(source = "customer.id", target = "customerId")
    @Mapping(source = "customer.fullName", target = "customerName")
    @Mapping(source = "restaurant.id", target = "restaurantId")
    @Mapping(source = "restaurant.name", target = "restaurantName")
    @Mapping(source = "orderItems", target = "items")
    @Mapping(source = "payment", target = "paymentMethod", qualifiedByName = "paymentMethodName")
    @Mapping(source = "payment", target = "paymentStatus", qualifiedByName = "paymentStatusName")
    OrderResponse toResponse(Order order);

    @Mapping(source = "menuItem.name", target = "menuItemName")
    @Mapping(target = "customizations", expression = "java(emptyCustomizations())")
    OrderItemResponse toItemResponse(OrderItem orderItem);

    @Named("paymentMethodName")
    default String paymentMethodName(Payment payment) {
        return payment != null && payment.getPaymentMethod() != null
                ? payment.getPaymentMethod().name()
                : null;
    }

    @Named("paymentStatusName")
    default String paymentStatusName(Payment payment) {
        return payment != null && payment.getStatus() != null
                ? payment.getStatus().name()
                : null;
    }

    default List<String> emptyCustomizations() {
        return new ArrayList<>();
    }
}
