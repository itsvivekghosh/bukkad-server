package com.bhukkad.restaurant.mapper;

@FunctionalInterface
public interface ImageUrlResolver {

    String resolvePublicUrl(String storedValue);
}
