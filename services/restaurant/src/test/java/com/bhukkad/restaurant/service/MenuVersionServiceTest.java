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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Versioned menu snapshots: snapshot, history, preview, publish, latestVersion.
 */
@ExtendWith(MockitoExtension.class)
class MenuVersionServiceTest {

    @Mock private MenuVersionRepository versionRepository;
    private MenuVersionService service;

    @BeforeEach
    void setUp() {
        // Constructed manually: ObjectMapper is not a mock, so @InjectMocks
        // would leave it null.
        service = new MenuVersionService(versionRepository, new ObjectMapper(),
                new org.springframework.transaction.support.TransactionTemplate(
                        // Executes callbacks inline; PG transaction semantics are
                        // exercised in the container suite, not here.
                        mock(org.springframework.transaction.PlatformTransactionManager.class)));
    }

    @Test
    void snapshot_firstVersionIsOne() {
        when(versionRepository.findByRestaurantIdOrderByVersionDesc(1L)).thenReturn(List.of());
        when(versionRepository.saveAndFlush(any(MenuVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        MenuVersion v = service.snapshot(1L, Map.of("categories", List.of()));

        assertThat(v.getRestaurantId()).isEqualTo(1L);
        assertThat(v.getVersion()).isEqualTo(1);
        assertThat(v.getSnapshotJson()).contains("categories");
        assertThat(v.getStatus()).isEqualTo(MenuVersion.MenuVersionStatus.DRAFT);
    }

    @Test
    void snapshot_incrementsFromLatest() {
        MenuVersion latest = new MenuVersion();
        latest.setRestaurantId(1L);
        latest.setVersion(3);
        when(versionRepository.findByRestaurantIdOrderByVersionDesc(1L)).thenReturn(List.of(latest));
        when(versionRepository.saveAndFlush(any(MenuVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        MenuVersion v = service.snapshot(1L, Map.of("name", "v4"));

        assertThat(v.getVersion()).isEqualTo(4);
    }

    @Test
    void history_returnsVersionsDescending() {
        when(versionRepository.findByRestaurantIdOrderByVersionDesc(1L)).thenReturn(List.of(new MenuVersion()));

        assertThat(service.history(1L)).hasSize(1);
    }

    @Test
    void preview_returnsParsedSnapshot() {
        MenuVersion stored = new MenuVersion();
        stored.setId(7L);
        stored.setRestaurantId(1L);
        stored.setSnapshotJson("{\"name\":\"Spring Menu\"}");
        when(versionRepository.findById(7L)).thenReturn(Optional.of(stored));

        Map<String, Object> snapshot = service.preview(7L);

        assertThat(snapshot).containsEntry("name", "Spring Menu");
    }

    @Test
    void preview_throws_whenMissing() {
        when(versionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.preview(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publish_flipsStatusToPublished() {
        MenuVersion stored = new MenuVersion();
        stored.setId(8L);
        stored.setRestaurantId(1L);
        stored.setStatus(MenuVersion.MenuVersionStatus.DRAFT);
        when(versionRepository.findById(8L)).thenReturn(Optional.of(stored));
        when(versionRepository.save(any(MenuVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        MenuVersion published = service.publish(8L);

        assertThat(published.getStatus()).isEqualTo(MenuVersion.MenuVersionStatus.PUBLISHED);
        assertThat(published.getPublishedAt()).isNotNull();
    }

    @Test
    void snapshot_retriesOnceOnVersionUniquenessRace() {
        MenuVersion latest = new MenuVersion();
        latest.setRestaurantId(1L);
        latest.setVersion(3);
        when(versionRepository.findByRestaurantIdOrderByVersionDesc(1L)).thenReturn(List.of(latest));
        // First flush collides on uq_menu_versions_restaurant_version, second wins.
        when(versionRepository.saveAndFlush(any(MenuVersion.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"))
                .thenAnswer(inv -> inv.getArgument(0));

        MenuVersion v = service.snapshot(1L, Map.of("k", "v"));

        assertThat(v.getVersion()).isEqualTo(4);
    }

    @Test
    void snapshot_failsAfterRetryLimit() {
        when(versionRepository.findByRestaurantIdOrderByVersionDesc(1L)).thenReturn(List.of());
        when(versionRepository.saveAndFlush(any(MenuVersion.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.snapshot(1L, Map.of("k", "v")))
                .isInstanceOf(com.bhukkad.common.error.BusinessException.class);
    }
}
