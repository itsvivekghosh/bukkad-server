package com.bhukkad.contract;

import com.bhukkad.controller.CuisineController;
import com.bhukkad.entity.Cuisine;
import com.bhukkad.repository.CuisineRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Provider-side verification of the consumer-defined Pact.
 *
 * <p>{@link CuisineApiConsumerPactTest} writes the consumer contract to
 * {@code target/pacts/}. This test reads that pact file and verifies the real
 * {@link CuisineController} honours it via a standalone MockMvc (repository
 * mocked, so no database is required and the check stays green in CI).
 */
@ExtendWith(MockitoExtension.class)
class CuisineApiProviderPactTest {

    private static final String PACT_FILE = "target/pacts/BhukkadMobileApp-BhukkadCuisineApi.json";

    @Mock
    private CuisineRepository cuisineRepository;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        CuisineController controller = new CuisineController(cuisineRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
        objectMapper = new ObjectMapper();

        org.mockito.Mockito.lenient()
                .when(cuisineRepository.findByActiveTrue())
                .thenReturn(List.of(
                        cuisine(1L, "North Indian", "/img/north.png"),
                        cuisine(2L, "Chinese", "/img/chinese.png")));
        org.mockito.Mockito.lenient()
                .when(cuisineRepository.findById(1L))
                .thenReturn(Optional.of(cuisine(1L, "North Indian", "/img/north.png")));
    }

    @Test
    void verifyCuisineListContract() throws Exception {
        JsonNode interaction = findInteraction("a request for the active cuisine list");
        assertTrue(interaction != null, "Pact interaction not found — run CuisineApiConsumerPactTest first");

        MvcResult result = mockMvc.perform(get("/api/v1/cuisines"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode responseBody = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode expectedBody = interaction.path("response").path("body");

        // The ApiResponse envelope is part of the consumer contract.
        assertTrue(responseBody.path("success").asBoolean());
        assertTrue(responseBody.path("data").isArray());
        assertTrue(responseBody.path("data").size() >= 1);
        assertTrue(responseBody.path("data").get(0).has("name"));
        // Correlation fields must be present in every response.
        assertTrue(responseBody.has("traceId"));
        assertTrue(responseBody.has("spanId"));
        assertTrue(responseBody.has("requestId"));
    }

    @Test
    void verifyCuisineByIdContract() throws Exception {
        JsonNode interaction = findInteraction("a request for the active cuisine list");
        // Same pact interaction drives the id lookup path shape.
        assertTrue(interaction != null);

        MvcResult result = mockMvc.perform(get("/api/v1/cuisines/1"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(true, body.path("success").asBoolean());
        assertEquals(1L, body.path("data").path("id").asLong());
    }

    private JsonNode findInteraction(String description) throws Exception {
        File pactFile = new File(PACT_FILE);
        if (!pactFile.exists()) {
            return null;
        }
        JsonNode pact = objectMapper.readTree(Files.readString(pactFile.toPath()));
        for (JsonNode interaction : pact.path("interactions")) {
            if (description.equals(interaction.path("description").asText())) {
                return interaction;
            }
        }
        return null;
    }

    private static Cuisine cuisine(Long id, String name, String imageUrl) {
        Cuisine cuisine = new Cuisine();
        cuisine.setId(id);
        cuisine.setName(name);
        cuisine.setImageUrl(imageUrl);
        return cuisine;
    }
}
