package com.bhukkad.survey.api.controller;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.survey.api.dto.response.ApiResponse;
import com.bhukkad.survey.api.dto.response.SurveyRatingsResponse;
import com.bhukkad.survey.api.dto.response.TrendingDishResponse;
import com.bhukkad.survey.domain.entity.DeliverySurvey;
import com.bhukkad.survey.domain.service.SurveyService;
import com.bhukkad.survey.domain.service.TrendingDishService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage tests for the survey HTTP boundary: JWT principal resolution and
 * the payload-coercion helpers ({@code asLong}/{@code asRating}) that accept
 * both JSON numbers and numeric strings from weaker clients.
 */
@ExtendWith(MockitoExtension.class)
class SurveyControllerTest {

    @Mock private SurveyService surveyService;
    @Mock private TrendingDishService trendingDishService;
    @InjectMocks private SurveyController controller;

    private static UsernamePasswordAuthenticationToken auth(long userId) {
        return new UsernamePasswordAuthenticationToken(
                new TokenPrincipal(userId, "c@t.test", "customer"), null, List.of());
    }

    private static DeliverySurvey persistedSurvey() {
        DeliverySurvey survey = new DeliverySurvey();
        survey.setId(99L);
        survey.setOrderId(1441L);
        survey.setRestaurantId(5L);
        survey.setRatingDelivery(5);
        survey.setRatingFood(4);
        survey.setRatingSpeed(3);
        survey.setComment("yummy");
        survey.setSubmittedAt(LocalDateTime.of(2026, 1, 2, 3, 4));
        survey.setCustomerId(7L);
        return survey;
    }

    @Test
    void submitSurvey_numericJson_rendersFlatProjection() {
        when(surveyService.submitSurvey(7L, 1441L, 5L, 5, 4, 3, "yummy"))
                .thenReturn(persistedSurvey());

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.submitSurvey(
                Map.of("orderId", 1441, "restaurantId", 5,
                        "ratingDelivery", 5, "ratingFood", 4, "ratingSpeed", 3,
                        "comment", "yummy"),
                auth(7L));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isTrue();
        assertThat(body.message()).isEqualTo("Survey submitted successfully");
        assertThat(body.data())
                .containsEntry("id", 99L)
                .containsEntry("orderId", 1441L)
                .containsEntry("ratingSpeed", 3)
                .containsEntry("comment", "yummy");
        assertThat(body.data()).containsKey("submittedAt");
    }

    @Test
    void submitSurvey_numericStrings_coerceToNumbers() {
        when(surveyService.submitSurvey(7L, 1441L, 5L, 5, 4, 3, null))
                .thenReturn(persistedSurvey());

        controller.submitSurvey(
                Map.of("orderId", "1441", "restaurantId", "5",
                        "ratingDelivery", "5", "ratingFood", "4", "ratingSpeed", "3"),
                auth(7L));

        verify(surveyService).submitSurvey(7L, 1441L, 5L, 5, 4, 3, null);
    }

    @Test
    void submitSurvey_garbageIdsAndRatings_passNullsThrough() {
        when(surveyService.submitSurvey(null, null, null, 0, 11, null, "x"))
                .thenReturn(persistedSurvey());

        controller.submitSurvey(
                Map.of("orderId", true, "restaurantId", "abc",
                        "ratingDelivery", 0.5d, "ratingFood", 11,
                        "ratingSpeed", "not-a-number", "comment", "x"),
                null); // no principal → customerId null

        verify(surveyService).submitSurvey(null, null, null, 0, 11, null, "x");
    }

    @Test
    void submitSurvey_oversizedNumericString_survivesParseGuard() {
        // A very long digit string matches \d+ but overflows Long.parseLong
        String huge = "9".repeat(40);
        when(surveyService.submitSurvey(7L, null, null, 4, null, null, null))
                .thenReturn(persistedSurvey());

        controller.submitSurvey(Map.of("orderId", huge, "ratingDelivery", 4), auth(7L));

        verify(surveyService).submitSurvey(7L, null, null, 4, null, null, null);
    }

    @Test
    void getSurveyRatings_delegatesAndWraps() {
        SurveyRatingsResponse ratings = new SurveyRatingsResponse(5L, 4.5, 4.25, 4.0, 8L);
        when(surveyService.getRestaurantRatings(5L)).thenReturn(ratings);

        ResponseEntity<ApiResponse<SurveyRatingsResponse>> response =
                controller.getSurveyRatings(5L);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(ratings);
        assertThat(response.getBody().success()).isTrue();
    }

    @Test
    void getTrendingDishes_forwardsLimit() {
        List<TrendingDishResponse> dishes =
                List.of(new TrendingDishResponse(1L, "Butter Chicken", 42L));
        when(trendingDishService.trending(3)).thenReturn(dishes);

        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);

        assertThat(controller.getTrendingDishes(3).getBody().data()).isEqualTo(dishes);
        verify(trendingDishService).trending(captor.capture());
        assertThat(captor.getValue()).isEqualTo(3);
    }
}
