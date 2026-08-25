package com.bhukkad.recommendation;

import com.bhukkad.entity.MenuItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationQueryServiceTest {

    @Mock
    private EntityManager entityManager;

    private RecommendationQueryService service;

    /** Creates a Query mock whose setParameter chain returns the same query. */
    private Query fluentQuery() {
        Query query = mock(Query.class);
        doAnswer(inv -> query).when(query).setParameter(anyString(), any());
        return query;
    }

    @BeforeEach
    void setUp() {
        service = new RecommendationQueryService(entityManager);
    }

    @Test
    void findCustomerItemFrequencies_executesNativeQuery() {
        Query query = fluentQuery();
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        doReturn(List.<Object[]>of(new Object[]{100L, 3L})).when(query).getResultList();

        List<Object[]> result = service.findCustomerItemFrequencies(5L, 10);

        assertEquals(1, result.size());
        assertEquals(100L, result.get(0)[0]);
        verify(query).setParameter("customerId", 5L);
        verify(query).setParameter("limit", 10);
    }

    @Test
    void findCoOrderedItems_emptyIds_returnsEmptyWithoutQuery() {
        List<Object[]> result = service.findCoOrderedItems(List.of(), 5L, 10);

        assertTrue(result.isEmpty());
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    void findCoOrderedItems_nonEmptyIds_executesQuery() {
        Query query = fluentQuery();
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        doReturn(List.<Object[]>of(new Object[]{200L, 2L})).when(query).getResultList();

        List<Object[]> result = service.findCoOrderedItems(List.of(100L), 5L, 10);

        assertEquals(1, result.size());
        verify(query).setParameter("menuItemIds", List.of(100L));
        verify(query).setParameter("excludeCustomerId", 5L);
        verify(query).setParameter("limit", 10);
    }

    @Test
    void findCustomerRestaurantAffinities_executesQuery() {
        Query query = fluentQuery();
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        doReturn(List.<Object[]>of(new Object[]{1L, 5L})).when(query).getResultList();

        List<Object[]> result = service.findCustomerRestaurantAffinities(5L, 25);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0)[0]);
        verify(query).setParameter("customerId", 5L);
        verify(query).setParameter("limit", 25);
    }

    @Test
    void hydrateAvailableItems_emptyIds_returnsEmpty() {
        List<MenuItem> result = service.hydrateAvailableItems(List.of());

        assertTrue(result.isEmpty());
        verify(entityManager, never()).createQuery(anyString());
    }

    @Test
    void hydrateAvailableItems_preservesOrderAndFiltersUnavailable() {
        MenuItem itemA = new MenuItem();
        itemA.setId(1L);
        itemA.setAvailable(true);
        MenuItem itemB = new MenuItem();
        itemB.setId(2L);
        itemB.setAvailable(false);
        MenuItem itemC = new MenuItem();
        itemC.setId(3L);
        itemC.setAvailable(true);

        Query query = fluentQuery();
        when(entityManager.createQuery(anyString())).thenReturn(query);
        // query returns items out of request order (1,3,2)
        doReturn(List.of(itemC, itemB, itemA)).when(query).getResultList();

        List<MenuItem> result = service.hydrateAvailableItems(List.of(1L, 2L, 3L, 1L));

        // order preserved per request ids, unavailable (2) dropped, duplicates collapsed
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals(3L, result.get(1).getId());
        verify(query).setParameter("ids", List.of(1L, 2L, 3L, 1L));
    }

    @Test
    void hydrateAvailableItems_nullAvailableTreatedAsUnavailable() {
        MenuItem item = new MenuItem();
        item.setId(1L);
        item.setAvailable(null);

        Query query = fluentQuery();
        when(entityManager.createQuery(anyString())).thenReturn(query);
        doReturn(List.of(item)).when(query).getResultList();

        List<MenuItem> result = service.hydrateAvailableItems(List.of(1L));

        assertTrue(result.isEmpty());
    }

    @Test
    void hydrateAvailableItems_unknownIdsSkipped() {
        MenuItem item = new MenuItem();
        item.setId(1L);
        item.setAvailable(true);

        Query query = fluentQuery();
        when(entityManager.createQuery(anyString())).thenReturn(query);
        doReturn(List.of(item)).when(query).getResultList();

        List<MenuItem> result = service.hydrateAvailableItems(List.of(1L, 999L));

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }
}