package com.bhukkad.search;

import com.bhukkad.search.entity.MenuItemSearchEntity;
import com.bhukkad.search.entity.RestaurantSearchEntity;
import com.bhukkad.search.repository.MenuItemSearchRepository;
import com.bhukkad.search.repository.RestaurantSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-08 option shipped off (P3): proves the V11 trigram ground work works when
 * the repository queries are invoked — extension present, both LIKE paths and
 * the similarity ranking behave against the real data, and typo tolerance is
 * genuine (a LIKE '%briyani%' finds nothing; similarity does).
 */
@SpringBootTest(properties = {
        "app.search.sync.enabled=false",       // no sweeps in this context
        "app.search.fuzzy.enabled=false"})     // gate OFF — queries invoked directly
class SearchFuzzyTrigramPostgresIntegrationTest extends AbstractSearchPostgresTest {

    @Autowired private RestaurantSearchRepository restaurantRepository;
    @Autowired private MenuItemSearchRepository menuItemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        menuItemRepository.deleteAll();
        restaurantRepository.deleteAll();
        restaurantRepository.saveAndFlush(restaurant("Hyderabadi Biryani House", "Slow-cooked dum biryani", "biryani, mughlai"));
        restaurantRepository.saveAndFlush(restaurant("Paneer Palace", "Pure veg thali", "indian, vegetarian"));
    }

    private RestaurantSearchEntity restaurant(String name, String description, String cuisine) {
        RestaurantSearchEntity r = new RestaurantSearchEntity();
        r.setName(name);
        r.setDescription(description);
        r.setCuisineSummary(cuisine);
        r.setIsActive(true);
        r.setIsOpen(true);
        return r;
    }

    @Test
    void v11Migration_landsExtensionAndTrigramIndexes() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class))
                .isEqualTo(1);
        for (String idx : List.of("idx_restaurant_search_name_trgm",
                "idx_menu_item_search_food_type_trgm")) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_indexes WHERE indexname = ?",
                    Integer.class, idx)).isEqualTo(1);
        }
    }

    @Test
    void defaultLikePath_stillFindsExactSubstring() {
        List<RestaurantSearchEntity> exact = restaurantRepository.searchText(
                "biryani", PageRequest.of(0, 10));
        assertThat(exact).extracting(RestaurantSearchEntity::getName)
                .containsExactly("Hyderabadi Biryani House");
    }

    @Test
    void similarityPath_findsTypoThatLikeCannot() {
        // Measured against the container: word_similarity('briyani',
        // 'Hyderabadi Biryani House') = 0.375 (> 0.3 cutoff); the same term
        // through LIKE ('%briyani%' typo) → zero rows:
        assertThat(restaurantRepository.searchText("briyani", PageRequest.of(0, 10))).isEmpty();

        List<RestaurantSearchEntity> fuzzy = restaurantRepository.searchTextFuzzy(
                "briyani", 0.3, PageRequest.of(0, 10));
        assertThat(fuzzy).isNotEmpty();
        assertThat(fuzzy.get(0).getName()).isEqualTo("Hyderabadi Biryani House");

        Double best = jdbcTemplate.queryForObject(
                "SELECT word_similarity('briyani', name) FROM restaurant_search "
                        + "WHERE name = 'Hyderabadi Biryani House'",
                Double.class);
        assertThat(best).isGreaterThan(0.3);
    }

    @Test
    void menuTrigramPath_ranksByGreatestSimilarity() {
        MenuItemSearchEntity chicken = new MenuItemSearchEntity();
        chicken.setName("Butter Chicken");
        chicken.setDescription("Creamy tomato gravy");
        chicken.setCategoryName("Main Course");
        chicken.setFoodType("NON-VEG");
        MenuItemSearchEntity daal = new MenuItemSearchEntity();
        daal.setName("Daal Makhani");
        daal.setCategoryName("Main Course");
        daal.setFoodType("VEG");
        menuItemRepository.saveAndFlush(chicken);
        menuItemRepository.saveAndFlush(daal);

        List<MenuItemSearchEntity> fuzzy = menuItemRepository.searchTextFuzzy(
                "main cours", 0.3, PageRequest.of(0, 10));

        assertThat(fuzzy).extracting(MenuItemSearchEntity::getName)
                .containsExactlyInAnyOrder("Butter Chicken", "Daal Makhani");
    }
}
