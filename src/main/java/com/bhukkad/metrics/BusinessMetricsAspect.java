package com.bhukkad.metrics;

import com.bhukkad.dto.response.BatchOrderResponse;
import com.bhukkad.dto.response.OrderResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Aspect
@Component
public class BusinessMetricsAspect {

    private final BusinessMetricsService businessMetricsService;

    public BusinessMetricsAspect(BusinessMetricsService businessMetricsService) {
        this.businessMetricsService = businessMetricsService;
    }

    @Pointcut("execution(* com.bhukkad.serviceImpl.OrderPlacementService.create*(..))")
    void orderPlacementCreate() {}

    @AfterReturning(pointcut = "orderPlacementCreate()", returning = "result")
    public void recordOrderCreated(JoinPoint joinPoint, Object result) {
        try {
            if (result instanceof OrderResponse order) {
                Double total = order.getTotalAmount();
                businessMetricsService.recordOrderCreated(total != null ? total : 0.0);
            } else if (result instanceof BatchOrderResponse batch) {
                List<OrderResponse> orders = batch.getOrders();
                if (orders != null && !orders.isEmpty()) {
                    double sum = orders.stream()
                            .mapToDouble(o -> o.getTotalAmount() != null ? o.getTotalAmount() : 0.0)
                            .sum();
                    businessMetricsService.recordOrderCreated(sum);
                } else {
                    businessMetricsService.recordOrderCreated(0.0);
                }
            } else if (result != null) {
                Double total = extractTotal(result);
                businessMetricsService.recordOrderCreated(total != null ? total : 0.0);
            } else {
                businessMetricsService.recordOrderCreated(0.0);
            }
        } catch (Exception e) {
            log.warn("Failed to record order metrics in aspect | method={} | error={}",
                    joinPoint.getSignature().toShortString(), e.getMessage());
        }
    }

    private static Double extractTotal(Object result) {
        try {
            var method = result.getClass().getMethod("getTotalAmount");
            Object val = method.invoke(result);
            if (val instanceof Number n) return n.doubleValue();
        } catch (Exception ignored) {}
        try {
            var method = result.getClass().getMethod("getTotal");
            Object val = method.invoke(result);
            if (val instanceof Number n) return n.doubleValue();
        } catch (Exception ignored) {}
        return null;
    }
}