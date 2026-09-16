package com.bhukkad.search;

import com.bhukkad.search.domain.entity.MenuItemSearchEntity;
import com.bhukkad.search.domain.entity.RestaurantSearchEntity;
import com.bhukkad.search.domain.repository.MenuItemSearchRepository;
import com.bhukkad.search.domain.repository.RestaurantSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB query validation (hot-path regression): validates that the search
 * service's required indexes exist and that the deterministic hot-path
 * queries (prefix, PK lookup, upsert) use them.
 *
 * <p>Note on leading-wildcard LIKE and trigram indexes:
 * the production {@code searchText} query wraps columns in {@code lower(...)}
 * with a leading {@code %} wildcard. PostgreSQL cannot use btree/expression
 * indexes for that pattern, and the GIN trigram index is only used by the
 * fuzzy/prefix paths. This test therefore validates the *index existence*
 * and the *deterministic index-backed paths* that are the actual CI gate
 * for query-plan regressions.</p>
 */
@SpringBootTest(properties = {
        "app.search.sync.enabled=false",
        "app.search.fuzzy.enabled=true"
})
class SearchQueryPlanValidationTest extends AbstractSearchPostgresTest {

    @Autowired private RestaurantSearchRepository restaurantRepository;
    @Autowired private MenuItemSearchRepository menuItemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        menuItemRepository.deleteAll();
        restaurantRepository.deleteAll();
        for (int i = 0; i < 200; i++) {
            RestaurantSearchEntity r = new RestaurantSearchEntity();
            r.setName("Biryani House " + i);
            r.setDescription("Slow-cooked dum biryani " + i);
            r.setCuisineSummary("biryani, mughlai");
            r.setIsActive(true);
            r.setIsOpen(true);
            restaurantRepository.saveAndFlush(r);
        }
        for (int i = 0; i < 200; i++) {
            MenuItemSearchEntity m = new MenuItemSearchEntity();
            m.setName("Chicken Biryani " + i);
            m.setDescription("Creamy " + i);
            m.setCategoryName("Main Course");
            m.setFoodType("NON-VEG");
            m.setRestaurantId(1L);
            m.setAvailable(true);
            menuItemRepository.saveAndFlush(m);
        }
    }

    @Test
    void requiredIndexesExist() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename IN ('restaurant_search','menu_item_search')",
                String.class);
        assertThat(indexes)
                .contains(
                        "idx_restaurant_search_name_trgm",
                        "idx_menu_item_search_name_trgm",
                        "idx_restaurant_search_name_lower_prefix",
                        "idx_menu_item_search_name_lower_prefix",
                        "restaurant_search_pkey",
                        "menu_item_search_pkey");
    }

    @Test
    void restaurantNamePrefix_usesExpressionIndex() {
        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN ANALYZE SELECT * FROM restaurant_search WHERE lower(name) LIKE ?",
                String.class, "biryani%"));
        assertThat(plan)
                .as("Prefix search should use expression index")
                .contains("Index Scan")
                .doesNotContain("Seq Scan");
    }

    @Test
    void menuItemNamePrefix_usesExpressionIndex() {
        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN ANALYZE SELECT * FROM menu_item_search WHERE lower(name) LIKE ?",
                String.class, "chicken%"));
        assertThat(plan)
                .as("Menu prefix search should use expression index")
                .contains("Index Scan")
                .doesNotContain("Seq Scan");
    }

    @Test
    void menuItemUpsert_usesPrimaryKeyIndex() {
        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN ANALYZE INSERT INTO menu_item_search (id, restaurant_id, name, description, price, available, restaurant_name) " +
                        "VALUES (9999, 1, 'Test', 'desc', 100, true, 'Test') ON CONFLICT (id) DO UPDATE SET restaurant_id = EXCLUDED.restaurant_id",
                String.class));
        assertThat(plan)
                .as("Upsert should use primary key index")
                .contains("Insert")
                .doesNotContain("Seq Scan");
    }

    @Test
    void menuItemFindByRestaurantId_usesIndex() {
        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN ANALYZE SELECT * FROM menu_item_search WHERE restaurant_id = ?",
                String.class, 1L));
        assertThat(plan)
                .as("Find by restaurant_id should use index")
                .contains("Index Scan")
                .doesNotContain("Seq Scan");
    }
}
