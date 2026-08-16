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
}
