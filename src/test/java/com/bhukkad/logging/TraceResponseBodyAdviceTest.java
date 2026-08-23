package com.bhukkad.logging;

import com.bhukkad.dto.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceResponseBodyAdviceTest {

    private final TraceResponseBodyAdvice advice = new TraceResponseBodyAdvice();

    @SuppressWarnings("unchecked")
    private static <T> Class<? extends HttpMessageConverter<T>> converterType() {
        return (Class<? extends HttpMessageConverter<T>>) (Class<?>) HttpMessageConverter.class;
    }

    @Test
    void supports_returnsTrue() {
        assertTrue(advice.supports(mock(MethodParameter.class), converterType()));
    }

    @Test
    void beforeBodyWrite_enrichesApiResponseAndAttachesHeaders() {
        ApiResponse<?> body = ApiResponse.success("data");
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        when(response.getHeaders()).thenReturn(headers);

        TraceContext.restore(Map.of(
                LoggingConstants.TRACE_ID, "trace-1",
                LoggingConstants.SPAN_ID, "span-1",
                LoggingConstants.REQUEST_ID, "req-1"));

        Object result = advice.beforeBodyWrite(
                body, mock(MethodParameter.class), MediaType.APPLICATION_JSON,
                converterType(), mock(ServerHttpRequest.class), response);

        assertSame(body, result);
        assertEquals("trace-1", body.getTraceId());
        assertEquals("span-1", body.getSpanId());
        assertEquals("req-1", body.getRequestId());
        assertEquals("trace-1", headers.getFirst(LoggingConstants.HEADER_TRACE_ID));
        assertEquals("span-1", headers.getFirst("X-Span-Id"));
        assertEquals("req-1", headers.getFirst(LoggingConstants.HEADER_REQUEST_ID));
        assertTrue(headers.containsKey(LoggingConstants.HEADER_EXPOSE));
        TraceContext.clear();
    }

    @Test
    void beforeBodyWrite_keepsExistingTraceFields() {
        ApiResponse<?> body = ApiResponse.success("data");
        body.setTraceId("already-set");
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        TraceContext.clear();

        Object result = advice.beforeBodyWrite(
                body, mock(MethodParameter.class), MediaType.APPLICATION_JSON,
                converterType(), mock(ServerHttpRequest.class), response);

        assertEquals("already-set", ((ApiResponse<?>) result).getTraceId());
    }

    @Test
    void beforeBodyWrite_ignoresUnsupportedHeaderFailure() {
        Object nonApiBody = "plain string";
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(response.getHeaders()).thenThrow(new UnsupportedOperationException("read-only"));

        Object result = advice.beforeBodyWrite(
                nonApiBody, mock(MethodParameter.class), MediaType.TEXT_PLAIN,
                converterType(), mock(ServerHttpRequest.class), response);

        assertSame(nonApiBody, result);
    }
}