package com.bhukkad.mapper;

import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.entity.Address;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AddressMapperTest {

    private final AddressMapper mapper = new AddressMapperImpl();

    @Test void toResponse_mapsAllFields() {
        Address address = new Address();
        address.setId(7L);
        address.setAddressLine1("123 MG Road");
        address.setAddressLine2("Floor 2");
        address.setCity("Bangalore");
        address.setState("Karnataka");
        address.setPincode("560001");
        address.setLandmark("Near Metro");
        address.setType(Address.AddressType.HOME);
        address.setLabel("Home");
        address.setLatitude(12.9716);
        address.setLongitude(77.5946);
        address.setIsDefault(true);

        AddressResponse response = mapper.toResponse(address);

        assertEquals(7L, response.getId());
        assertEquals("123 MG Road", response.getAddressLine1());
        assertEquals("Floor 2", response.getAddressLine2());
        assertEquals("Bangalore", response.getCity());
        assertEquals("Karnataka", response.getState());
        assertEquals("560001", response.getPincode());
        assertEquals("Near Metro", response.getLandmark());
        assertEquals("HOME", response.getType());
        assertEquals("Home", response.getLabel());
        assertEquals(12.9716, response.getLatitude());
        assertEquals(77.5946, response.getLongitude());
        assertEquals(true, response.getIsDefault());
    }

    @Test void toResponse_nullAddress_returnsNull() {
        assertNull(mapper.toResponse(null));
    }

    @Test void toResponse_minimalFields() {
        Address address = new Address();
        address.setId(1L);
        address.setAddressLine1("Only line");

        AddressResponse response = mapper.toResponse(address);

        assertEquals("Only line", response.getAddressLine1());
        assertNull(response.getCity());
        assertNull(response.getType());
    }
}