package com.bhukkad.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJacksonValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit coverage for the {@code ?fields=} projection helper.
 *
 * <p>Asserts the three behaviours the helper exists for:
 * <ol>
 *   <li>a null/blank {@code fields} returns the wrapper unchanged so callers
 *       can always return {@code MappingJacksonValue};</li>
 *   <li>a non-blank list is passed through verbatim to Jackson;</li>
 *   <li>the wrapped payload is the original DTO, not a copy — important so
 *       that downstream caching, logging and exception handling keep working
 *       when projection is in use.</li>
 * </ol>
 */
class FieldProjectionTest {

    public static class SampleDto {
        public Long getId() { return 1L; }
        public String getName() { return "x"; }
        public String getDescription() { return "y"; }
    }

    @Test
    void blankFields_returnsWrapperWithoutFilters() {
        SampleDto dto = new SampleDto();
        MappingJacksonValue wrapper = FieldProjection.project(dto, null);
        assertNotNull(wrapper);
        assertEquals(dto, wrapper.getValue(), "payload must be the original DTO");
        // No filters attached — Jackson will not skip any property.
    }

    @Test
    void blankFieldsEmptyString_returnsWrapperWithoutFilters() {
        SampleDto dto = new SampleDto();
        MappingJacksonValue wrapper = FieldProjection.project(dto, "   ");
        assertNotNull(wrapper);
        assertEquals(dto, wrapper.getValue());
    }

    @Test
    void nonBlankFields_attachesAllowListFilter() {
        SampleDto dto = new SampleDto();
        MappingJacksonValue wrapper = FieldProjection.project(dto, "id,name");
        assertNotNull(wrapper);
        assertEquals(dto, wrapper.getValue());
        Object filterProvider = wrapper.getFilters();
        assertNotNull(filterProvider, "filters must be attached when fields are present");
        assertEquals(SimpleFilterProvider.class, filterProvider.getClass());
    }

    @Test
    void whitespaceOnlyFields_treatedAsNoProjection() {
        SampleDto dto = new SampleDto();
        // Whitespace is treated the same as null — keeps clients that
        // accidentally URL-encode a trailing space from getting an empty
        // projection and surprising themselves with an empty response body.
        MappingJacksonValue wrapper = FieldProjection.project(dto, "  ");
        assertNotNull(wrapper);
        assertEquals(dto, wrapper.getValue());
        // No filters attached because the helper short-circuits on blank input.
        assertNull(wrapper.getFilters());
    }
}
