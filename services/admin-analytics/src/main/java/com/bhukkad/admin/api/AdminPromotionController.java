package com.bhukkad.admin.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-admin promotion management (monolith parity): campaigns and
 * promo-banners CRUD over the admin-owned {@code promotion_campaigns} /
 * {@code promo_banners} tables. ADMIN-gated; the catalogues seed the home
 * feed and checkout discount flows.
 */
@RestController
@RequestMapping("/api/v1/admin/promotions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminPromotionController {

    private final JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // Campaigns
    // ------------------------------------------------------------------

    @GetMapping("/campaigns")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> campaigns() {
        return jdbcTemplate.queryForList(
                "SELECT id, name, campaign_type, description, discount_percent, min_order_amount, "
                        + "is_active, starts_at, ends_at FROM promotion_campaigns ORDER BY id DESC");
    }

    @PostMapping("/campaigns")
    @Transactional
    public ResponseEntity<Map<String, Object>> createCampaign(
            @RequestBody(required = false) Map<String, Object> body) {
        String name = stringOrNull(body, "name");
        if (name == null || name.isBlank()) {
            throw new BusinessException("name is required");
        }
        String type = String.valueOf(body == null ? null : body.getOrDefault("campaignType", "DISCOUNT"));
        Double discount = body == null || body.get("discountPercent") == null ? 10.0
                : Double.valueOf(String.valueOf(body.get("discountPercent")));
        jdbcTemplate.update(
                "INSERT INTO promotion_campaigns (name, campaign_type, discount_percent, "
                        + "starts_at, ends_at, is_active) VALUES (?, ?, ?, NOW(), NOW() + INTERVAL '30 days', TRUE)",
                name.trim(), type, discount);
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("name", name.trim());
        created.put("campaignType", type);
        created.put("discountPercent", discount);
        created.put("isActive", true);
        return ResponseEntity.status(201).body(created);
    }

    @PutMapping("/campaigns/{campaignId}")
    @Transactional
    public Map<String, Object> updateCampaign(@PathVariable Long campaignId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        requireCampaign(campaignId);
        if (body != null) {
            if (stringOrNull(body, "name") != null) {
                jdbcTemplate.update("UPDATE promotion_campaigns SET name = ? WHERE id = ?",
                        stringOrNull(body, "name").trim(), campaignId);
            }
            if (body.get("isActive") != null) {
                jdbcTemplate.update("UPDATE promotion_campaigns SET is_active = ? WHERE id = ?",
                        Boolean.parseBoolean(String.valueOf(body.get("isActive"))), campaignId);
            }
            if (body.get("discountPercent") != null) {
                jdbcTemplate.update("UPDATE promotion_campaigns SET discount_percent = ? WHERE id = ?",
                        new BigDecimal(String.valueOf(body.get("discountPercent"))), campaignId);
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>(requireCampaign(campaignId));
        resp.put("message", "Campaign updated");
        return resp;
    }

    @DeleteMapping("/campaigns/{campaignId}")
    @Transactional
    public Map<String, Object> deleteCampaign(@PathVariable Long campaignId) {
        requireCampaign(campaignId);
        jdbcTemplate.update("UPDATE promotion_campaigns SET is_active = FALSE WHERE id = ?", campaignId);
        return Map.of("message", "Campaign deactivated", "id", campaignId);
    }

    private Map<String, Object> requireCampaign(Long campaignId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, campaign_type, discount_percent, is_active FROM promotion_campaigns WHERE id = ?",
                campaignId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Campaign not found: " + campaignId);
        }
        return rows.get(0);
    }

    // ------------------------------------------------------------------
    // Banners
    // ------------------------------------------------------------------

    @GetMapping("/banners")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> banners() {
        return jdbcTemplate.queryForList(
                "SELECT id, title, subtitle, image_url, action_type, action_target, "
                        + "display_order, is_active FROM promo_banners ORDER BY display_order, id");
    }

    @PostMapping("/banners")
    @Transactional
    public ResponseEntity<Map<String, Object>> createBanner(
            @RequestBody(required = false) Map<String, Object> body) {
        String title = stringOrNull(body, "title");
        if (title == null || title.isBlank()) {
            throw new BusinessException("title is required");
        }
        String imageUrl = String.valueOf(body == null ? null : body.getOrDefault("imageUrl", ""));
        String targetUrl = String.valueOf(body == null ? null : body.getOrDefault("targetUrl", ""));
        int position = body == null || body.get("position") == null ? 0
                : ((Number) body.get("position")).intValue();
        jdbcTemplate.update(
                "INSERT INTO promo_banners (title, image_url, action_target, display_order, is_active) "
                        + "VALUES (?, ?, ?, ?, TRUE)",
                title.trim(), imageUrl, targetUrl, position);
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("title", title.trim());
        created.put("imageUrl", imageUrl);
        created.put("targetUrl", targetUrl);
        created.put("position", position);
        created.put("isActive", true);
        return ResponseEntity.status(201).body(created);
    }

    @PutMapping("/banners/{bannerId}")
    @Transactional
    public Map<String, Object> updateBanner(@PathVariable Long bannerId,
                                            @RequestBody(required = false) Map<String, Object> body) {
        requireBanner(bannerId);
        if (body != null) {
            if (stringOrNull(body, "title") != null) {
                jdbcTemplate.update("UPDATE promo_banners SET title = ? WHERE id = ?",
                        stringOrNull(body, "title").trim(), bannerId);
            }
            if (body.get("isActive") != null) {
                jdbcTemplate.update("UPDATE promo_banners SET is_active = ? WHERE id = ?",
                        Boolean.parseBoolean(String.valueOf(body.get("isActive"))), bannerId);
            }
            if (body.get("position") != null) {
                jdbcTemplate.update("UPDATE promo_banners SET display_order = ? WHERE id = ?",
                        ((Number) body.get("position")).intValue(), bannerId);
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>(requireBanner(bannerId));
        resp.put("message", "Banner updated");
        return resp;
    }

    @DeleteMapping("/banners/{bannerId}")
    @Transactional
    public Map<String, Object> deleteBanner(@PathVariable Long bannerId) {
        requireBanner(bannerId);
        jdbcTemplate.update("UPDATE promo_banners SET is_active = FALSE WHERE id = ?", bannerId);
        return Map.of("message", "Banner deactivated", "id", bannerId);
    }

    private Map<String, Object> requireBanner(Long bannerId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, title, image_url, display_order, is_active FROM promo_banners WHERE id = ?",
                bannerId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Banner not found: " + bannerId);
        }
        return rows.get(0);
    }

    private static String stringOrNull(Map<String, Object> body, String key) {
        return body == null || body.get(key) == null ? null : String.valueOf(body.get(key));
    }
}
