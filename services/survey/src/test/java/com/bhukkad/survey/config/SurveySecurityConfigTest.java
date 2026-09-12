package com.bhukkad.survey.config;

import com.bhukkad.common.security.PlatformJwtAuthFilter;
import com.bhukkad.survey.AbstractSurveyPostgresTest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security surface of the survey service: the home/trending and
 * survey-ratings reads are public (monolith parity), survey submission
 * requires the customer JWT, and the entry point answers 401 with the
 * platform JSON envelope rather than a redirect or empty body.
 */
@SpringBootTest(
        properties = "app.auth.jwt.secret=0123456789abcdef0123456789abcdef",
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SurveySecurityConfigTest extends AbstractSurveyPostgresTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;
    @Autowired private SurveySecurityConfig config;

    @Test
    void filterChainAndJwtFilterAreWired() {
        assertThat(context.getBean(SecurityFilterChain.class)).isNotNull();
        assertThat(context.getBean(PlatformJwtAuthFilter.class)).isNotNull();
    }

    @Test
    void trendingIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/home/trending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void surveyRatingsIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/restaurants/public/5/survey-ratings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurantId").value(5));
    }

    @Test
    void submitSurveyWithoutJwt_is401WithEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/survey")
                        .contentType("application/json")
                        .content("{\"orderId\":1,\"ratingFood\":5}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void unknownPathRequiresAuthentication() throws Exception {
        // anyRequest().authenticated(): unknown paths never surface the MVC
        // 404 to anonymous callers — the /error matcher exists so that
        // AUTHENTICATED no-handler forwards keep their real status.
        mockMvc.perform(get("/api/v1/nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void entryPointWritesUnauthorizedEnvelope() throws Exception {
        AuthenticationEntryPoint entryPoint = config.surveyAuthenticationEntryPoint();
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest(), response,
                new org.springframework.security.core.AuthenticationException("nope") {
                });

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentAsString())
                .isEqualTo("{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}");
    }
}
