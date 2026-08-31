package com.bhukkad.restaurant.service;

import com.bhukkad.restaurant.domain.MenuVersion;
import com.bhukkad.restaurant.domain.MenuVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Versioned menu snapshots: create snapshot with next version, history.
 */
@ExtendWith(MockitoExtension.class)
class MenuVersionServiceTest {

    @Mock private MenuVersionRepository versionRepository;
    private MenuVersionService service;

    @BeforeEach
    void setUp() {
        // Constructed manually: the real ObjectMapper is not a mock, so
        // @InjectMocks would leave it null.
        service = new MenuVersionService(versionRepository, new ObjectMapper());
    }

    @Test
    void snapshot_firstVersionIsOne() {
        when(versionRepository.findByRestaurantIdOrderByVersionDesc(1L)).thenReturn(List.of());
        when(versionRepository.save(any(MenuVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        MenuVersion v = service.snapshot(1L, Map.of("categories", List.of()));

        assertThat(v.getRestaurantId()).isEqualTo(1L);
        assertThat(v.getVersion()).isEqualTo(1);
        assertThat(v.getSnapshotJson()).contains("categories");
    }

    @Test
    void snapshot_incrementsFromLatest() {
        MenuVersion latest = new MenuVersion();
        latest.setRestaurantId(1L);
        latest.setVersion(3);
        when(versionRepository.findByRestaurantIdOrderByVersionDesc(1L)).thenReturn(List.of(latest));
        when(versionRepository.save(any(MenuVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        MenuVersion v = service.snapshot(1L, Map.of("name", "v4"));

        assertThat(v.getVersion()).isEqualTo(4);
    }

    @Test
    void history_retunsVersionsDescending() {
        when(versionRepository.findByRestaurantIdOrderByVersionDesc(1L)).thenReturn(List.of(new MenuVersion()));

        assertThat(service.history(1L)).hasSize(1);
    }
}