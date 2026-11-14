package com.bhukkad.common.web;

import com.bhukkad.common.error.PageSizeExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Interceptor that rejects oversized page sizes with {@link
 * PageSizeExceededException} (HTTP 400) instead of silently clamping them.
 * This prevents unbounded result-set materialization and memory pressure
 * under fan-out traffic (audit P1-01 / SV-11 / SV-15).
 *
 * <p>Registered in {@link WebConfig} via {@code addInterceptors} so it applies
 * to every controller across all services that component-scan
 * {@code com.bhukkad.common}.
 */
public class PageSizeInterceptor implements HandlerInterceptor {

    private final int maxPageSize;

    public PageSizeInterceptor(@Value("${app.pagination.max-page-size:100}") int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        String sizeRaw = request.getParameter("size");
        if (sizeRaw != null) {
            try {
                int requested = Integer.parseInt(sizeRaw.trim());
                if (requested > maxPageSize) {
                    throw new PageSizeExceededException(requested, maxPageSize);
                }
            } catch (NumberFormatException ex) {
                // Non-numeric size: let Spring apply its default page size.
            }
        }
        return true;
    }
}
