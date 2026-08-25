package com.bhukkad.notificationservice;

import com.bhukkad.notificationservice.preference.CustomerNotificationPreference;
import com.bhukkad.notificationservice.preference.NotificationPreferenceService;
import com.bhukkad.notificationservice.web.GlobalExceptionHandler;
import com.bhukkad.notificationservice.web.NotificationPreferenceController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationPreferenceControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        NotificationPreferenceService service = new NotificationPreferenceService(
                new CustomerNotificationPreference.Store());
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationPreferenceController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getPreferencesReturnsDefaults() throws Exception {
        mockMvc.perform(get("/api/v1/customers/42/notification-preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(true))
                .andExpect(jsonPath("$.whatsappEnabled").value(true));
    }

    @Test
    void updatePreferencesPersistsChanges() throws Exception {
        mockMvc.perform(put("/api/v1/customers/42/notification-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"smsEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.smsEnabled").value(false));

        mockMvc.perform(get("/api/v1/customers/42/notification-preferences"))
                .andExpect(jsonPath("$.smsEnabled").value(false));
    }

    @Test
    void updateWithoutBodyReturns400() throws Exception {
        mockMvc.perform(put("/api/v1/customers/42/notification-preferences")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}
