package com.bhukkad.personalization.domain.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationQueryServiceImplTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ResultSet resultSet;

    private RecommendationQueryServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new RecommendationQueryServiceImpl(jdbcTemplate);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object[] runMapper(RowMapper captured, int rowNum) throws SQLException {
        return (Object[]) captured.mapRow(resultSet, rowNum);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findCustomerItemFrequencies_mapsIdsAndFrequencies() throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RowMapper> mapper = ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(7L), eq(5)))
                .thenReturn(List.of());

        assertThat(service.findCustomerItemFrequencies(7L, 5)).isEmpty();

        verify(jdbcTemplate).query(sql.capture(), mapper.capture(), eq(7L), eq(5));
        assertThat(sql.getValue()).contains("menu_item_id").contains("frequency");
        when(resultSet.getLong("menu_item_id")).thenReturn(41L);
        when(resultSet.getLong("frequency")).thenReturn(3L);
        assertThat(runMapper(mapper.getValue(), 0)).containsExactly(41L, 3L);
    }

    @Test
    void findCoOrderedItems_nullOrEmptyItemIds_skipsQuery() {
        assertThat(service.findCoOrderedItems(List.of(), 1L, 10)).isEmpty();
        assertThat(service.findCoOrderedItems(null, 1L, 10)).isEmpty();

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findCoOrderedItems_bindsIdsThenCustomerThenLimit() throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RowMapper> mapper = ArgumentCaptor.forClass(RowMapper.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        assertThat(service.findCoOrderedItems(List.of(11L, 12L), 7L, 30)).isEmpty();

        verify(jdbcTemplate).query(sql.capture(), mapper.capture(), params.capture());
        assertThat(sql.getValue()).contains("IN (?,?)");
        assertThat(params.getValue()).containsExactly(11L, 12L, 7L, 30);
        when(resultSet.getLong("menu_item_id")).thenReturn(12L);
        when(resultSet.getLong("co_occurrence")).thenReturn(4L);
        assertThat(runMapper(mapper.getValue(), 0)).containsExactly(12L, 4L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findCustomerRestaurantAffinities_mapsRestaurantAndVisits() throws Exception {
        ArgumentCaptor<RowMapper> mapper = ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(7L), eq(9)))
                .thenReturn(List.of());

        service.findCustomerRestaurantAffinities(7L, 9);

        verify(jdbcTemplate).query(anyString(), mapper.capture(), eq(7L), eq(9));
        when(resultSet.getLong("restaurant_id")).thenReturn(3L);
        when(resultSet.getLong("visits")).thenReturn(8L);
        assertThat(runMapper(mapper.getValue(), 0)).containsExactly(3L, 8L);
        verify(jdbcTemplate, never()).queryForList(anyString());
    }
}
