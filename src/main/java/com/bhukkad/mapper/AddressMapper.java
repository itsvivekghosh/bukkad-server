package com.bhukkad.mapper;

import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.entity.Address;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AddressMapper {

    AddressResponse toResponse(Address address);
}
