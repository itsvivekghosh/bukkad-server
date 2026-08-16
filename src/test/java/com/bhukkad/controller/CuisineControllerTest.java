package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.entity.Cuisine;
import com.bhukkad.repository.CuisineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CuisineControllerTest {

    @Mock
    private CuisineRepository cuisineRepository;

    @InjectMocks
    private CuisineController cuisineController;

    @Test
    void getAllCuisines_returnsActiveCuisines() {
        Cuisine cuisine = new Cuisine();
        cuisine.setId(1L);
        cuisine.setName("Indian");
        when(cuisineRepository.findByActiveTrue()).thenReturn(List.of(cuisine));

        ResponseEntity<ApiResponse<List<Cuisine>>> response = cuisineController.getAllCuisines();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().getData().size());
        assertEquals("Indian", response.getBody().getData().get(0).getName());
        verify(cuisineRepository).findByActiveTrue();
    }

    @Test
    void getCuisineById_returnsCuisineWhenFound() {
        Cuisine cuisine = new Cuisine();
        cuisine.setId(1L);
        cuisine.setName("Italian");
        when(cuisineRepository.findById(1L)).thenReturn(Optional.of(cuisine));

        ResponseEntity<ApiResponse<Cuisine>> response = cuisineController.getCuisineById(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(cuisine, response.getBody().getData());
    }

    @Test
    void getCuisineById_throwsWhenMissing() {
        when(cuisineRepository.findById(99L)).thenReturn(Optional.empty());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> cuisineController.getCuisineById(99L));

        assertEquals("Cuisine not found", ex.getMessage());
    }
}
