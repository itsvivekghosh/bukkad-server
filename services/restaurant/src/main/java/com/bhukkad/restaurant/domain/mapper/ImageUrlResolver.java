package com.bhukkad.restaurant.domain.mapper;

@FunctionalInterface
public interface ImageUrlResolver {

    String resolvePublicUrl(String storedValue);
}
