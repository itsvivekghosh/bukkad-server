package com.bhukkad.restaurant.service;

import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.MenuItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

/**
 * PERF-3 item 7: the autocomplete trie used to be built from findAll() at
 * startup and then went stale forever. Startup build is kept; a cheap
 * scheduled rebuild into a swapped immutable index refreshes it. The index is
 * pod-local, so (deliberate deviation from the batch text) there is no
 * ShedLock here — every replica must rebuild its own copy, and the restaurant
 * module has no shedlock dependency ("no new runtime deps" gate).
 */
@ExtendWith(MockitoExtension.class)
class AutocompleteRefreshTest {

    @Mock private MenuItemRepository menuItemRepository;

    private static MenuItem named(String name) {
        MenuItem mi = new MenuItem();
        mi.setName(name);
        return mi;
    }

    @Test
    void periodicRefresh_isScheduledWithBoundedDelay() throws Exception {
        var method = AutocompleteService.class.getMethod("refreshPeriodically");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled).as("periodic refresh wired").isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${app.search.autocomplete-refresh-ms:900000}");
    }

    @Test
    void refresh_picksUpMenuChangesViaAtomicSwap() {
        AutocompleteService service = new AutocompleteService(menuItemRepository);
        when(menuItemRepository.findAll()).thenReturn(List.of(named("Butter Chicken")));
        assertThat(service.suggest("butt", 5)).containsExactly("butter chicken");

        when(menuItemRepository.findAll()).thenReturn(List.of(named("Butter Chicken"), named("Butter Naan")));
        service.refreshPeriodically();
        assertThat(service.suggest("buttern", 5)).isEmpty();
        assertThat(service.suggest("butt", 5)).contains("butter naan");
    }

    @Test
    void refreshFailure_keepsPreviousIndexServing() {
        AutocompleteService service = new AutocompleteService(menuItemRepository);
        when(menuItemRepository.findAll()).thenReturn(List.of(named("Paneer")));
        service.warm();
        when(menuItemRepository.findAll()).thenThrow(new RuntimeException("db blip"));

        assertThatCode(service::refreshPeriodically).doesNotThrowAnyException();
        assertThat(service.suggest("pan", 5)).containsExactly("paneer");
    }
}
