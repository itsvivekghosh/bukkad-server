package com.bhukkad.web;

import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import org.springframework.http.converter.json.MappingJacksonValue;

/**
 * Generic {@code ?fields=id,name,rating} projection helper.
 *
 * <p>Most list and detail endpoints return DTOs whose payload is several times
 * larger than the fields the client actually needs. Sprinkling that payload
 * over slow mobile links wastes bandwidth and battery; this helper lets a
 * controller wrap its response so Jackson omits everything the client did not
 * ask for.
 *
 * <p>Wire-up:
 * <ul>
 *   <li>The {@link JsonFilters} Jackson configuration registers a no-op
 *       {@code bhukkadFieldSelection} filter globally so any DTO can opt in by
 *       adding {@code @com.fasterxml.jackson.annotation.JsonFilter("bhukkadFieldSelection")}
 *       to its class declaration.</li>
 *   <li>A controller that wants to expose {@code ?fields=} calls
 *       {@link #project(Object, String)} and returns the resulting
 *       {@link MappingJacksonValue} in place of the plain DTO.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 *   @GetMapping("/orders/{id}")
 *   public ResponseEntity<MappingJacksonValue> get(@PathVariable long id,
 *           @RequestParam(required = false) String fields) {
 *       OrderResponse order = orderService.getById(id);
 *       return ResponseEntity.ok(FieldProjection.project(order, fields));
 *   }
 * }</pre>
 *
 * <p>Endpoints that do not opt in keep their existing payload; no DTO is
 * modified by default. Unknown field names are silently ignored — clients
 * evolving their projections can deploy new fields independently of the
 * server.
 */
public final class FieldProjection {

    /** Jackson filter id shared with the global configuration. */
    public static final String FILTER_ID = "bhukkadFieldSelection";

    private FieldProjection() {}

    /**
     * Wraps the payload so Jackson drops every property whose name is not
     * listed in {@code fields}.
     *
     * @param payload the response DTO (or page wrapper)
     * @param fields  comma-separated allow-list; {@code null} or blank means
     *                "no projection" and the wrapper is still returned (so the
     *                controller signature is identical whether or not the
     *                caller passes {@code ?fields=})
     * @return a Jackson-aware wrapper the controller can return directly
     */
    public static <T> MappingJacksonValue project(T payload, String fields) {
        MappingJacksonValue wrapper = new MappingJacksonValue(payload);
        if (fields == null || fields.isBlank()) {
            // No projection requested — the wrapper is still produced so the
            // controller can be called with or without ?fields= without
            // changing its return type.
            return wrapper;
        }
        String[] keep = fields.split(",");
        SimpleFilterProvider provider = new SimpleFilterProvider()
                .addFilter(FILTER_ID, SimpleBeanPropertyFilter.filterOutAllExcept(keep));
        wrapper.setFilters(provider);
        return wrapper;
    }
}
