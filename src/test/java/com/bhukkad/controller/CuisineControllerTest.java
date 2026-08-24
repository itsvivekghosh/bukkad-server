package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.entity.Cuisine;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CuisineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CuisineControllerTest {

    @Mock
    private CuisineRepository cuisineRepository;

    @InjectMocks
    private CuisineController controller;

    @Test void getAllCuisines_returnsActiveList() {
        Cuisine c = new Cuisine();
        c.setId(1L);
        when(cuisineRepository.findByActiveTrue()).thenReturn(List.of(c));

        ResponseEntity<ApiResponse<List<Cuisine>>> resp = controller.getAllCuisines();

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(1, resp.getBody().getData().size());
    }

    @Test void getCuisineById_found_returnsCuisine() {
        Cuisine c = new Cuisine();
        c.setId(5L);
        when(cuisineRepository.findById(5L)).thenReturn(Optional.of(c));

        ResponseEntity<ApiResponse<Cuisine>> resp = controller.getCuisineById(5L);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(5L, resp.getBody().getData().getId());
    }

    @Test void getCuisineById_notFound_throwsResourceNotFound() {
        when(cuisineRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> controller.getCuisineById(999L));
        verify(cuisineRepository).findById(999L);
    }
}
