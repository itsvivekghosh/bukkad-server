package com.bhukkad.mapper;

import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.entity.Address;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the MapStruct {@link AddressMapper}.
 */
class AddressMapperTest {

    private final AddressMapper addressMapper = Mappers.getMapper(AddressMapper.class);

    @Test
    void toResponse_mapsAllFields() {
        Address address = new Address();
        address.setId(1L);
        address.setAddressLine1("42 Residency Road");
        address.setAddressLine2("2nd Floor");
        address.setCity("Bangalore");
        address.setState("Karnataka");
        address.setPincode("560025");
        address.setLandmark("Near Metro");
        address.setType(Address.AddressType.HOME);
        address.setLabel("Home");
        address.setLatitude(12.9716);
        address.setLongitude(77.5946);
        address.setIsDefault(true);

        AddressResponse response = addressMapper.toResponse(address);

        assertEquals(1L, response.getId());
        assertEquals("42 Residency Road", response.getAddressLine1());
        assertEquals("2nd Floor", response.getAddressLine2());
        assertEquals("Bangalore", response.getCity());
        assertEquals("Karnataka", response.getState());
        assertEquals("560025", response.getPincode());
        assertEquals("Near Metro", response.getLandmark());
        assertEquals("HOME", response.getType());
        assertEquals("Home", response.getLabel());
        assertEquals(12.9716, response.getLatitude());
        assertEquals(77.5946, response.getLongitude());
        assertTrue(response.getIsDefault());
    }

    @Test
    void toResponse_handlesNullOptionalFields() {
        Address address = new Address();
        address.setId(2L);
        address.setAddressLine1("1 Main St");
        address.setCity("Mumbai");
        address.setPincode("400001");

        AddressResponse response = addressMapper.toResponse(address);

        assertEquals(2L, response.getId());
        assertEquals("1 Main St", response.getAddressLine1());
        assertNull(response.getAddressLine2());
        assertNull(response.getLandmark());
        assertNull(response.getType());
        assertFalse(response.getIsDefault());
    }
}
