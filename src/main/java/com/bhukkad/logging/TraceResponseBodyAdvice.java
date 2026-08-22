package com.bhukkad.logging;

import com.bhukkad.dto.response.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice
public class TraceResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        attachTraceHeaders(response);
        if (body instanceof ApiResponse<?> apiResponse) {
            enrich(apiResponse);
        }
        return body;
    }

    private void attachTraceHeaders(ServerHttpResponse response) {
        try {
            String traceId = TraceContext.getTraceId();
            String requestId = TraceContext.getRequestId();
            String spanId = TraceContext.getSpanId();
            if (traceId != null) {
                response.getHeaders().set(LoggingConstants.HEADER_TRACE_ID, traceId);
            }
            if (spanId != null) {
                response.getHeaders().set("X-Span-Id", spanId);
            }
            if (requestId != null) {
                response.getHeaders().set(LoggingConstants.HEADER_REQUEST_ID, requestId);
            }
            response.getHeaders().set(LoggingConstants.HEADER_EXPOSE,
                    LoggingConstants.HEADER_TRACE_ID + ", " + LoggingConstants.HEADER_REQUEST_ID + ", X-Span-Id");
        } catch (UnsupportedOperationException ignored) {
            // Exception-handler responses may expose read-only headers; trace ids remain in ApiResponse body.
        }
    }

    private void enrich(ApiResponse<?> response) {
        if (response.getTraceId() == null) {
            response.setTraceId(TraceContext.getTraceId());
        }
        if (response.getSpanId() == null) {
            response.setSpanId(TraceContext.getSpanId());
        }
        if (response.getRequestId() == null) {
            response.setRequestId(TraceContext.getRequestId());
        }
    }
}
