package com.bhukkad.search;

import com.bhukkad.search.entity.MenuItemSearchEntity;
import com.bhukkad.search.entity.RestaurantSearchEntity;
import com.bhukkad.search.repository.MenuItemSearchRepository;
import com.bhukkad.search.repository.RestaurantSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-002 pg_trgm substrate (migration V11) on real PostgreSQL. Asserts BOTH
 * search modes against the same seeded data:
 *
 * <ul>
 *   <li><b>Default (flag off)</b> — the bounded LIKE path: a typo'd term
 *       finds nothing (exact-substring semantics).</li>
 *   <li><b>Fuzzy (app.search.fuzzy.enabled=true)</b> — the trigram similarity
 *       path: the same typo'd term finds the seeded row.</li>
 * </ul>
 *
 * The flag itself only routes inside {@code SearchServiceImpl} (covered by
 * {@code SearchBoundedQueryTest}); here the two repository paths are proven
 * against the real extension + GIN indexes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SearchFuzzyPostgresIntegrationTest extends AbstractSearchPostgresTest {

    @Autowired private RestaurantSearchRepository restaurantRepository;
    @Autowired private MenuItemSearchRepository menuItemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM menu_item_search");
        jdbcTemplate.update("DELETE FROM restaurant_search");
        restaurantRepository.upsertFromEvent(1L, "Udupi House",
                "South Indian vegetarian thali", "/img/udupi.png",
                true, true, 4.5, 120, "south indian, dosa");
        menuItemRepository.upsertFromEvent(11L, 1L, "Masala Dosa",
                "Crispy dosa with potato filling", 90.0, null, null,
                true, "VEG", true, "/img/dosa.png", 15, true, "Udupi House");
    }

    @Test
    void migration_installsTrgmExtensionAndGinIndexes() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class))
                .isEqualTo(1);
        Integer trgmIndexes = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE indexname IN (
                    'idx_restaurant_search_name_trgm',
                    'idx_restaurant_search_description_trgm',
                    'idx_restaurant_search_cuisine_trgm',
                    'idx_menu_item_search_name_trgm',
                    'idx_menu_item_search_description_trgm',
                    'idx_menu_item_search_category_trgm')
                """, Integer.class);
        assertThat(trgmIndexes).isEqualTo(6);
    }

    @Test
    void defaultLikePath_typoFindsNothing_fuzzyPathFindsIt() {
        // "udupi huose" (transposed) — exact substring fails on the default path...
        String typo = com.bhukkad.search.serviceImpl.SearchServiceImpl.escapeLike("udupi huose");
        assertThat(restaurantRepository.searchText(typo, PageRequest.of(0, 50))).isEmpty();

        // ...and succeeds on the trigram similarity path (flag-on surface).
        List<RestaurantSearchEntity> fuzzy = restaurantRepository.searchTextFuzzy("udupi huose", 50);
        assertThat(fuzzy).extracting(RestaurantSearchEntity::getName).containsExactly("Udupi House");
    }

    @Test
    void fuzzyPath_ranksBySimilarity_andCoversMenuItems() {
        List<MenuItemSearchEntity> fuzzy = menuItemRepository.searchTextFuzzy("masla dosa", 50);

        assertThat(fuzzy).extracting(MenuItemSearchEntity::getName).containsExactly("Masala Dosa");

        // A close-but-not-identical cuisine term also matches the restaurant.
        List<RestaurantSearchEntity> byCuisine =
                restaurantRepository.searchTextFuzzy("south indain dosa", 50);
        assertThat(byCuisine).extracting(RestaurantSearchEntity::getName)
                .contains("Udupi House");
    }

    @Test
    void fuzzyPath_limitBoundsTheResultSet() {
        assertThat(restaurantRepository.searchTextFuzzy("udupi", 0)).isEmpty();
    }
}
