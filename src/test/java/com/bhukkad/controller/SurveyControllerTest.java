package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.SurveyRatingsResponse;
import com.bhukkad.dto.response.TrendingDishResponse;
import com.bhukkad.entity.DeliverySurvey;
import com.bhukkad.entity.Order;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.survey.SurveyService;
import com.bhukkad.survey.TrendingDishService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SurveyControllerTest {

    @Mock
    private SurveyService surveyService;
    @Mock
    private TrendingDishService trendingDishService;
    @Mock
    private SecurityUtils securityUtils;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SurveyController controller = new SurveyController(surveyService, trendingDishService, securityUtils);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private DeliverySurvey survey(Long id) {
        DeliverySurvey survey = new DeliverySurvey();
        survey.setId(id);
        Order order = new Order();
        order.setId(100L);
        survey.setOrder(order);
        survey.setRatingDelivery(5);
        survey.setRatingFood(4);
        survey.setRatingSpeed(3);
        survey.setComment("Good");
        survey.setSubmittedAt(LocalDateTime.now());
        return survey;
    }

    @Test
    void submitSurvey_numericIds_returnsProjection() throws Exception {
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(surveyService.submitSurvey(eq(5L), eq(100L), eq(5), eq(4), eq(3), eq("Good")))
                .thenReturn(survey(1L));

        mockMvc.perform(post("/api/v1/reviews/survey")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":100,\"ratingDelivery\":5,\"ratingFood\":4,\"ratingSpeed\":3,\"comment\":\"Good\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(100))
                .andExpect(jsonPath("$.data.ratingDelivery").value(5));
    }

    @Test
    void submitSurvey_stringIds_toleratedAsNumbers() throws Exception {
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(surveyService.submitSurvey(eq(5L), eq(100L), eq(5), eq(4), eq(3), isNull()))
                .thenReturn(survey(1L));

        mockMvc.perform(post("/api/v1/reviews/survey")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"100\",\"ratingDelivery\":\"5\",\"ratingFood\":\"4\",\"ratingSpeed\":\"3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(100));
    }

    @Test
    void submitSurvey_invalidNonNumericIds_passNull() throws Exception {
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(surveyService.submitSurvey(eq(5L), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(survey(1L));

        mockMvc.perform(post("/api/v1/reviews/survey")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"abc\",\"ratingDelivery\":\"xyz\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void submitSurvey_surveyWithoutOrder_projectsNullOrderId() throws Exception {
        DeliverySurvey survey = new DeliverySurvey();
        survey.setId(2L);
        survey.setRatingDelivery(1);
        survey.setRatingFood(1);
        survey.setRatingSpeed(1);
        survey.setSubmittedAt(LocalDateTime.now());
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(surveyService.submitSurvey(anyLong(), any(), any(), any(), any(), any())).thenReturn(survey);

        mockMvc.perform(post("/api/v1/reviews/survey")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").doesNotExist());
    }

    @Test
    void getSurveyRatings_returnsRatings() throws Exception {
        SurveyRatingsResponse ratings = new SurveyRatingsResponse(10L, 4.5, 4.0, 3.5, 50L);
        when(surveyService.getRestaurantRatings(10L)).thenReturn(ratings);

        mockMvc.perform(get("/api/v1/restaurants/public/10/survey-ratings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ratingDelivery").value(4.5));
    }

    @Test
    void getTrendingDishes_defaultLimit() throws Exception {
        when(trendingDishService.trending(10)).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/home/trending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void getTrendingDishes_customLimit() throws Exception {
        TrendingDishResponse dish = new TrendingDishResponse(1L, "Biryani", 10L);
        when(trendingDishService.trending(5)).thenReturn(List.of(dish));

        mockMvc.perform(get("/api/v1/home/trending?limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Biryani"));
    }
}