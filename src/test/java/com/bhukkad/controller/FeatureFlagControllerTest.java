package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.featureflag.FeatureFlagProperties;
import com.bhukkad.featureflag.FeatureFlagService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FeatureFlagControllerTest {

    private FeatureFlagController controller(FeatureFlagService service) {
        return new FeatureFlagController(service);
    }

    private FeatureFlagService service() {
        FeatureFlagProperties props = new FeatureFlagProperties();
        props.getFlags().put("checkout.new", true);
        return new FeatureFlagService(props);
    }

    @Test
    void getFlag_returnsEffectiveValue() {
        FeatureFlagController c = controller(service());
        ResponseEntity<ApiResponse<Boolean>> resp = c.getFlag("checkout.new");
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(resp.getBody().isSuccess());
        assertTrue(resp.getBody().getData());
    }

    @Test
    void getFlag_unknownKey_returnsFalse() {
        FeatureFlagController c = controller(service());
        ResponseEntity<ApiResponse<Boolean>> resp = c.getFlag("missing.flag");
        assertEquals(200, resp.getStatusCode().value());
        assertFalse(resp.getBody().getData());
    }

    @Test
    void setFlag_updatesValue() {
        FeatureFlagController c = controller(service());
        ResponseEntity<ApiResponse<Boolean>> resp = c.setFlag("checkout.new", false);
        assertEquals(200, resp.getStatusCode().value());
        assertFalse(resp.getBody().getData());
    }

    @Test
    void setFlag_null_revertsToConfig() {
        FeatureFlagService svc = service();
        FeatureFlagController c = controller(svc);
        c.setFlag("checkout.new", false);
        assertFalse(svc.isEnabled("checkout.new"));

        ResponseEntity<ApiResponse<Boolean>> resp = c.setFlag("checkout.new", null);
        assertTrue(resp.getBody().getData());
    }

    @Test
    void getAllFlags_returnsSnapshot() {
        FeatureFlagController c = controller(service());
        ResponseEntity<ApiResponse<Map<String, Boolean>>> resp = c.getAllFlags();
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody().getData());
        assertTrue(resp.getBody().getData().containsKey("checkout.new"));
    }
}