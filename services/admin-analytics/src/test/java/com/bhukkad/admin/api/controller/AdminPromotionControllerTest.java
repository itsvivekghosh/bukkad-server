package com.bhukkad.admin.api.controller;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPromotionControllerTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @InjectMocks private AdminPromotionController controller;

    private static final String CAMPAIGN_ROW_SQL =
            "SELECT id, name, campaign_type, discount_percent, is_active FROM promotion_campaigns WHERE id = ?";
    private static final String BANNER_ROW_SQL =
            "SELECT id, title, image_url, display_order, is_active FROM promo_banners WHERE id = ?";

    private List<Map<String, Object>> campaignRow() {
        return List.of(Map.of("id", 5L, "name", "Diwali", "discount_percent", new BigDecimal("15")));
    }

    private List<Map<String, Object>> bannerRow() {
        return List.of(Map.of("id", 7L, "title", "Festival", "display_order", 1));
    }

    @Test
    void campaigns_listsThroughJdbc() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(campaignRow());

        assertThat(controller.campaigns()).hasSize(1);
    }

    @Test
    void createCampaign_insertsWithDefaultsWhenOptionalFieldsAbsent() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        ResponseEntity<Map<String, Object>> response =
                controller.createCampaign(Map.of("name", "  Diwali  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody())
                .containsEntry("name", "Diwali")
                .containsEntry("campaignType", "DISCOUNT")
                .containsEntry("discountPercent", 10.0)
                .containsEntry("isActive", true);
        verify(jdbcTemplate).update(anyString(), eq("Diwali"), eq("DISCOUNT"), eq(10.0));
    }

    @Test
    void createCampaign_honoursExplicitTypeAndDiscount() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        ResponseEntity<Map<String, Object>> response = controller.createCampaign(
                Map.of("name", "X", "campaignType", "FLAT", "discountPercent", 22.5));

        assertThat(response.getBody())
                .containsEntry("campaignType", "FLAT")
                .containsEntry("discountPercent", 22.5);
    }

    @Test
    void createCampaign_nullBodyOrMissingName_isRejected() {
        assertThatThrownBy(() -> controller.createCampaign(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("name is required");
        assertThatThrownBy(() -> controller.createCampaign(Map.of("name", "  ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void updateCampaign_appliesAllProvidedFields() {
        when(jdbcTemplate.queryForList(eq(CAMPAIGN_ROW_SQL), any(Object[].class))).thenReturn(campaignRow());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        Map<String, Object> resp = controller.updateCampaign(5L,
                Map.of("name", "New name", "isActive", false, "discountPercent", 33.5));

        assertThat(resp).containsEntry("message", "Campaign updated");
        verify(jdbcTemplate).update(contains("SET name"), eq("New name"), eq(5L));
        verify(jdbcTemplate).update(contains("SET is_active"), eq(false), eq(5L));
        verify(jdbcTemplate).update(contains("discount_percent"), eq(new BigDecimal("33.5")), eq(5L));
    }

    @Test
    void updateCampaign_nullBodyOnlyEchoesRow() {
        when(jdbcTemplate.queryForList(eq(CAMPAIGN_ROW_SQL), any(Object[].class))).thenReturn(campaignRow());

        assertThat(controller.updateCampaign(5L, null)).containsEntry("message", "Campaign updated");
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void updateCampaign_missingCampaign_isNotFound() {
        when(jdbcTemplate.queryForList(eq(CAMPAIGN_ROW_SQL), any(Object[].class))).thenReturn(List.of());

        assertThatThrownBy(() -> controller.updateCampaign(99L, Map.of("name", "x")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Campaign not found");
    }

    @Test
    void deleteCampaign_deactivates() {
        when(jdbcTemplate.queryForList(eq(CAMPAIGN_ROW_SQL), any(Object[].class))).thenReturn(campaignRow());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThat(controller.deleteCampaign(5L)).containsEntry("message", "Campaign deactivated");
    }

    @Test
    void deleteCampaign_missing_isNotFound() {
        when(jdbcTemplate.queryForList(eq(CAMPAIGN_ROW_SQL), any(Object[].class))).thenReturn(List.of());

        assertThatThrownBy(() -> controller.deleteCampaign(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void banners_listsThroughJdbc() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(bannerRow());

        assertThat(controller.banners()).hasSize(1);
    }

    @Test
    void createBanner_insertsWithDefaults() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        ResponseEntity<Map<String, Object>> response = controller.createBanner(Map.of("title", "  Fest  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody())
                .containsEntry("title", "Fest")
                .containsEntry("imageUrl", "")
                .containsEntry("targetUrl", "")
                .containsEntry("position", 0);
    }

    @Test
    void createBanner_honoursExplicitFields() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        ResponseEntity<Map<String, Object>> response = controller.createBanner(
                Map.of("title", "T", "imageUrl", "http://img", "targetUrl", "http://t", "position", 3));

        assertThat(response.getBody())
                .containsEntry("imageUrl", "http://img")
                .containsEntry("targetUrl", "http://t")
                .containsEntry("position", 3);
    }

    @Test
    void createBanner_missingTitle_isRejected() {
        assertThatThrownBy(() -> controller.createBanner(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("title is required");
    }

    @Test
    void updateBanner_appliesProvidedFields() {
        when(jdbcTemplate.queryForList(eq(BANNER_ROW_SQL), any(Object[].class))).thenReturn(bannerRow());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        Map<String, Object> resp = controller.updateBanner(7L,
                Map.of("title", "New", "isActive", true, "position", 9.0));

        assertThat(resp).containsEntry("message", "Banner updated");
        verify(jdbcTemplate).update(contains("SET title"), eq("New"), eq(7L));
        verify(jdbcTemplate).update(contains("is_active"), eq(true), eq(7L));
        verify(jdbcTemplate).update(contains("display_order"), eq(9), eq(7L));
    }

    @Test
    void updateBanner_nullBodyEchoesRow() {
        when(jdbcTemplate.queryForList(eq(BANNER_ROW_SQL), any(Object[].class))).thenReturn(bannerRow());

        assertThat(controller.updateBanner(7L, null)).containsEntry("message", "Banner updated");
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void updateBanner_missing_isNotFound() {
        when(jdbcTemplate.queryForList(eq(BANNER_ROW_SQL), any(Object[].class))).thenReturn(List.of());

        assertThatThrownBy(() -> controller.updateBanner(1L, Map.of()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Banner not found");
    }

    @Test
    void deleteBanner_deactivates() {
        when(jdbcTemplate.queryForList(eq(BANNER_ROW_SQL), any(Object[].class))).thenReturn(bannerRow());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThat(controller.deleteBanner(7L)).containsEntry("message", "Banner deactivated");
    }

    @Test
    void deleteBanner_missing_isNotFound() {
        when(jdbcTemplate.queryForList(eq(BANNER_ROW_SQL), any(Object[].class))).thenReturn(List.of());

        assertThatThrownBy(() -> controller.deleteBanner(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static String contains(String fragment) {
        return org.mockito.ArgumentMatchers.argThat(
                sql -> sql != null && sql.contains(fragment));
    }
}
