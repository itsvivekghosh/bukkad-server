package com.bhukkad.controller;

import com.bhukkad.dto.request.MenuCategoryRequest;
import com.bhukkad.dto.request.MenuItemRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.MenuCategoryResponse;
import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.service.MenuService;
import com.bhukkad.cache.http.HttpCacheSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;

@Tag("regression")
@ExtendWith(MockitoExtension.class)
public class MenuControllerTest {

    @Mock
    private MenuService menuService;

    @Mock
    private HttpCacheSupport httpCacheSupport;

    @InjectMocks
    private MenuController menuController;

    @Test
    void createCategory_returnsCreated() {
        MenuCategoryRequest request = new MenuCategoryRequest();
        MenuCategoryResponse created = new MenuCategoryResponse();
        when(menuService.createCategory(1L, request)).thenReturn(created);

        ResponseEntity<ApiResponse<MenuCategoryResponse>> response = menuController.createCategory(1L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Category created successfully", response.getBody().getMessage());
        assertEquals(created, response.getBody().getData());
    }

    @Test
    void getCategoriesByRestaurant_returnsList() {
        List<MenuCategoryResponse> categories = List.of(new MenuCategoryResponse());
        when(menuService.getCategoriesByRestaurant(1L)).thenReturn(categories);
        when(httpCacheSupport.buildCacheHeaders(anyString(), anyString())).thenReturn(new org.springframework.http.HttpHeaders());

        ResponseEntity<ApiResponse<List<MenuCategoryResponse>>> response =
                menuController.getCategoriesByRestaurant(1L, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(categories, response.getBody().getData());
    }

    @Test
    void updateCategory_returnsUpdated() {
        MenuCategoryRequest request = new MenuCategoryRequest();
        MenuCategoryResponse updated = new MenuCategoryResponse();
        when(menuService.updateCategory(2L, request)).thenReturn(updated);

        ResponseEntity<ApiResponse<MenuCategoryResponse>> response = menuController.updateCategory(2L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Category updated successfully", response.getBody().getMessage());
        assertEquals(updated, response.getBody().getData());
    }

    @Test
    void deleteCategory_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = menuController.deleteCategory(2L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Category deleted successfully", response.getBody().getMessage());
        verify(menuService).deleteCategory(2L);
    }

    @Test
    void createMenuItem_returnsCreated() {
        MenuItemRequest request = new MenuItemRequest();
        MenuItemResponse item = new MenuItemResponse();
        when(menuService.createMenuItem(request)).thenReturn(item);

        ResponseEntity<ApiResponse<MenuItemResponse>> response = menuController.createMenuItem(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Menu item created successfully", response.getBody().getMessage());
        assertEquals(item, response.getBody().getData());
    }

    @Test
    void getMenuItemById_returnsItem() {
        MenuItemResponse item = new MenuItemResponse();
        when(menuService.getMenuItemById(9L)).thenReturn(item);
        when(httpCacheSupport.buildCacheHeaders(anyString(), anyString())).thenReturn(new org.springframework.http.HttpHeaders());

        ResponseEntity<ApiResponse<MenuItemResponse>> response = menuController.getMenuItemById(9L, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(item, response.getBody().getData());
    }

    @Test
    void getMenuItemsByCategory_returnsList() {
        List<MenuItemResponse> items = List.of(new MenuItemResponse());
        when(menuService.getMenuItemsByCategory(3L)).thenReturn(items);
        when(httpCacheSupport.buildCacheHeaders(anyString(), anyString())).thenReturn(new org.springframework.http.HttpHeaders());

        ResponseEntity<ApiResponse<List<MenuItemResponse>>> response =
                menuController.getMenuItemsByCategory(3L, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(items, response.getBody().getData());
    }

    @Test
    void getMenuItemsByRestaurant_returnsList() {
        List<MenuItemResponse> items = List.of(new MenuItemResponse());
        when(menuService.getMenuItemsByRestaurant(1L)).thenReturn(items);
        when(httpCacheSupport.buildCacheHeaders(anyString(), anyString())).thenReturn(new org.springframework.http.HttpHeaders());

        ResponseEntity<ApiResponse<List<MenuItemResponse>>> response =
                menuController.getMenuItemsByRestaurant(1L, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(items, response.getBody().getData());
    }

    @Test
    void updateMenuItem_returnsUpdated() {
        MenuItemRequest request = new MenuItemRequest();
        MenuItemResponse item = new MenuItemResponse();
        when(menuService.updateMenuItem(9L, request)).thenReturn(item);

        ResponseEntity<ApiResponse<MenuItemResponse>> response = menuController.updateMenuItem(9L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Menu item updated successfully", response.getBody().getMessage());
        assertEquals(item, response.getBody().getData());
    }

    @Test
    void deleteMenuItem_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = menuController.deleteMenuItem(9L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Menu item deleted successfully", response.getBody().getMessage());
        verify(menuService).deleteMenuItem(9L);
    }

    @Test
    void toggleItemAvailability_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = menuController.toggleItemAvailability(9L, true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Item availability updated", response.getBody().getMessage());
        verify(menuService).toggleItemAvailability(9L, true);
    }

    @Test
    void getBestsellers_returnsList() {
        List<MenuItemResponse> items = List.of(new MenuItemResponse());
        when(menuService.getBestsellers(1L)).thenReturn(items);

        ResponseEntity<ApiResponse<List<MenuItemResponse>>> response = menuController.getBestsellers(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(items, response.getBody().getData());
    }

    @Test
    void getRecommended_returnsList() {
        List<MenuItemResponse> items = List.of(new MenuItemResponse());
        when(menuService.getRecommended(1L)).thenReturn(items);

        ResponseEntity<ApiResponse<List<MenuItemResponse>>> response = menuController.getRecommended(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(items, response.getBody().getData());
    }

    @Test
    void searchMenuItems_returnsMatches() {
        List<MenuItemResponse> items = List.of(new MenuItemResponse());
        when(menuService.searchMenuItems("biryani")).thenReturn(items);

        ResponseEntity<ApiResponse<List<MenuItemResponse>>> response = menuController.searchMenuItems("biryani");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(items, response.getBody().getData());
    }

    @Test
    void getMenuItemsByIds_emptyIds_returnsEmptyList() {
        ResponseEntity<ApiResponse<List<MenuItemResponse>>> response =
                menuController.getMenuItemsByIds(List.of());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().getData().isEmpty());
        verify(menuService, never()).getMenuItemsByIds(any());
    }

    @Test
    void getMenuItemsByIds_tooManyIds_throwsBusinessException() {
        java.util.List<Long> many = java.util.stream.LongStream.rangeClosed(1, 101)
                .boxed().collect(java.util.stream.Collectors.toList());
        assertThrows(com.bhukkad.exception.BusinessException.class,
                () -> menuController.getMenuItemsByIds(many));
        verify(menuService, never()).getMenuItemsByIds(any());
    }

    @Test
    void getMenuItemsByIds_withIds_returnsItems() {
        MenuItemResponse response = new MenuItemResponse();
        response.setId(1L);
        when(menuService.getMenuItemsByIds(List.of(1L))).thenReturn(List.of(response));

        ResponseEntity<ApiResponse<List<MenuItemResponse>>> result =
                menuController.getMenuItemsByIds(List.of(1L));

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(1, result.getBody().getData().size());
        verify(menuService).getMenuItemsByIds(List.of(1L));
    }

    // ===== Batch B: conditional-GET 304 branches + image upload / low-stock endpoints =====

    private org.springframework.http.HttpHeaders headersWithEtag(String etag) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setETag(etag);
        return headers;
    }

    @Test
    void getCategoriesByRestaurant_notModified_returns304() {
        List<MenuCategoryResponse> categories = List.of(new MenuCategoryResponse());
        when(menuService.getCategoriesByRestaurant(1L)).thenReturn(categories);
        when(httpCacheSupport.buildCacheHeaders(anyString(), anyString()))
                .thenReturn(headersWithEtag("\"v1\""));
        when(httpCacheSupport.isNotModified("\"v1\"", "\"v1\"")).thenReturn(true);

        ResponseEntity<ApiResponse<List<MenuCategoryResponse>>> response =
                menuController.getCategoriesByRestaurant(1L, "\"v1\"");

        assertEquals(HttpStatus.NOT_MODIFIED, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void getMenuItemById_notModified_returns304() {
        MenuItemResponse item = new MenuItemResponse();
        when(menuService.getMenuItemById(5L)).thenReturn(item);
        when(httpCacheSupport.buildCacheHeaders(anyString(), anyString()))
                .thenReturn(headersWithEtag("\"abc\""));
        when(httpCacheSupport.isNotModified("\"abc\"", "\"abc\"")).thenReturn(true);

        ResponseEntity<ApiResponse<MenuItemResponse>> response =
                menuController.getMenuItemById(5L, "\"abc\"");

        assertEquals(HttpStatus.NOT_MODIFIED, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void getMenuItemById_modified_returns200() {
        MenuItemResponse item = new MenuItemResponse();
        item.setId(5L);
        when(menuService.getMenuItemById(5L)).thenReturn(item);
        when(httpCacheSupport.buildCacheHeaders(anyString(), anyString()))
                .thenReturn(headersWithEtag("\"abc\""));
        when(httpCacheSupport.isNotModified(null, "\"abc\"")).thenReturn(false);

        ResponseEntity<ApiResponse<MenuItemResponse>> response =
                menuController.getMenuItemById(5L, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(item, response.getBody().getData());
    }

    @Test
    void getMenuItemsByCategory_notModified_returns304() {
        when(menuService.getMenuItemsByCategory(8L)).thenReturn(List.of());
        when(httpCacheSupport.buildCacheHeaders(anyString(), anyString()))
                .thenReturn(headersWithEtag("\"c8\""));
        when(httpCacheSupport.isNotModified("\"c8\"", "\"c8\"")).thenReturn(true);

        ResponseEntity<ApiResponse<List<MenuItemResponse>>> response =
                menuController.getMenuItemsByCategory(8L, "\"c8\"");

        assertEquals(HttpStatus.NOT_MODIFIED, response.getStatusCode());
    }

    @Test
    void getMenuItemsByRestaurant_notModified_returns304() {
        when(menuService.getMenuItemsByRestaurant(4L)).thenReturn(List.of());
        when(httpCacheSupport.buildCacheHeaders(anyString(), anyString()))
                .thenReturn(headersWithEtag("\"r4\""));
        when(httpCacheSupport.isNotModified("\"r4\"", "\"r4\"")).thenReturn(true);

        ResponseEntity<ApiResponse<List<MenuItemResponse>>> response =
                menuController.getMenuItemsByRestaurant(4L, "\"r4\"");

        assertEquals(HttpStatus.NOT_MODIFIED, response.getStatusCode());
    }

    @Test
    void createMenuItemImageUploadUrl_returnsUrl() {
        com.bhukkad.dto.request.MenuImageUploadRequest request =
                new com.bhukkad.dto.request.MenuImageUploadRequest();
        com.bhukkad.dto.response.MenuImageUploadResponse uploadResponse =
                com.bhukkad.dto.response.MenuImageUploadResponse.builder()
                        .imageKey("images/1/2/x.jpg")
                        .uploadUrl("https://presigned")
                        .expiresInSeconds(900)
                        .build();
        when(menuService.createMenuItemImageUploadUrl(2L, request)).thenReturn(uploadResponse);

        ResponseEntity<ApiResponse<com.bhukkad.dto.response.MenuImageUploadResponse>> response =
                menuController.createMenuItemImageUploadUrl(2L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Upload URL created", response.getBody().getMessage());
        assertEquals(uploadResponse, response.getBody().getData());
    }

    @Test
    void getLowStockItems_withThreshold_returnsItems() {
        List<MenuItemResponse> items = List.of(new MenuItemResponse());
        when(menuService.getLowStockItems(4L, 10)).thenReturn(items);

        ResponseEntity<ApiResponse<List<MenuItemResponse>>> response =
                menuController.getLowStockItems(4L, 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(items, response.getBody().getData());
    }

    @Test
    void getLowStockItems_withoutThreshold_returnsItems() {
        when(menuService.getLowStockItems(4L, null)).thenReturn(List.of());

        ResponseEntity<ApiResponse<List<MenuItemResponse>>> response =
                menuController.getLowStockItems(4L, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().getData().isEmpty());
    }
}
