package com.bhukkad.catalog.restaurant.domain.mapper;

@FunctionalInterface
public interface ImageUrlResolver {

    String resolvePublicUrl(String storedValue);
}
