package com.bhukkad.restaurant.service;

import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.restaurant.domain.Cuisine;
import com.bhukkad.restaurant.domain.CuisineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cuisine catalog: list all, create with duplicate guard.
 */
@ExtendWith(MockitoExtension.class)
class CuisineServiceTest {

    @Mock private CuisineRepository cuisineRepository;
    @InjectMocks private CuisineService service;

    @Test
    void all_returnsCatalog() {
        when(cuisineRepository.findAll()).thenReturn(List.of(new Cuisine(), new Cuisine()));
        assertThat(service.all()).hasSize(2);
    }

    @Test
    void create_savesNewCuisine() {
        when(cuisineRepository.findAll()).thenReturn(List.of());
        when(cuisineRepository.save(any(Cuisine.class))).thenAnswer(inv -> {
            Cuisine c = inv.getArgument(0);
            c.setId(9L);
            return c;
        });

        Cuisine saved = service.create("North Indian");

        assertThat(saved.getName()).isEqualTo("North Indian");
        assertThat(saved.getId()).isEqualTo(9L);
        verify(cuisineRepository).save(saved);
    }

    @Test
    void create_duplicateCaseInsensitive_throws() {
        Cuisine existing = new Cuisine();
        existing.setName("North Indian");
        when(cuisineRepository.findAll()).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.create("north indian"))
                .isInstanceOf(DuplicateRequestException.class)
                .hasMessageContaining("already exists");
    }
}
