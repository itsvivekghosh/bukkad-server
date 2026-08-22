package com.bhukkad.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@Aspect
@Component
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);
    private static final Logger perfLogger = LoggerFactory.getLogger(LoggingConstants.PERFORMANCE_LOGGER);
    private static final long SLOW_THRESHOLD_MS = 500;

    private final ObjectMapper objectMapper;

    @Value("${app.debug:false}")
    private boolean debugMode;

    public LoggingAspect() {
        this.objectMapper = new ObjectMapper();
    }

    @Pointcut("within(com.bhukkad.controller..*)")
    public void controllerMethods() {}

    @Pointcut("within(com.bhukkad.serviceImpl..*)")
    public void serviceImplMethods() {}

    @Pointcut("within(com.bhukkad.repository..*)")
    public void repositoryMethods() {}

    @Pointcut("execution(* com.bhukkad.serviceImpl.OrderServiceImpl.*(..))")
    public void orderServiceMethods() {}

    @Pointcut("execution(* com.bhukkad.serviceImpl.PaymentServiceImpl.*(..))")
    public void paymentServiceMethods() {}

    @Pointcut("execution(* com.bhukkad.serviceImpl.AuthServiceImpl.*(..))")
    public void authServiceMethods() {}

    // ==================== CONTROLLER - DEBUG ONLY (dev only) ====================

    @Around("controllerMethods()")
    public Object logController(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();

        // Only log in debug/dev mode
        if (debugMode) {
            log.debug(toJson("CONTROLLER_ENTER", className, methodName, null, null, sanitizeArgs(joinPoint.getArgs())));
        }

        long startTime = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;

            // Only log in debug/dev mode
            if (debugMode) {
                log.debug(toJson("CONTROLLER_EXIT", className, methodName, duration, "SUCCESS", null));
            }

            return result;
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - startTime;
            // Handled domain exceptions are WARN-logged by GlobalExceptionHandler;
            // only unexpected failures are logged here at ERROR.
            logAtAppropriateLevel(ex, "CONTROLLER_ERROR", className, methodName, duration);
            throw ex;
        }
    }

    // ==================== SERVICE - DEBUG ONLY (dev only), WARN for slow ====================

    @Around("serviceImplMethods()")
    public Object logService(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();

        if (debugMode) {
            log.debug(toJson("SERVICE_ENTER", className, methodName, null, null, null));
        }

        long startTime = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;

            if (duration > SLOW_THRESHOLD_MS) {
                // WARN - always logged even in prod
                log.warn(toJson("SERVICE_SLOW", className, methodName, duration, "SLOW", null));
            } else if (debugMode) {
                log.debug(toJson("SERVICE_EXIT", className, methodName, duration, "SUCCESS", null));
            }

            return result;
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - startTime;
            // Handled domain exceptions are WARN-logged by GlobalExceptionHandler;
            // only unexpected failures are logged here at ERROR.
            logAtAppropriateLevel(ex, "SERVICE_ERROR", className, methodName, duration);
            throw ex;
        }
    }

    // ==================== ORDER - INFO (always logged) ====================

    @Around("orderServiceMethods()")
    public Object logOrder(ProceedingJoinPoint joinPoint) throws Throwable {
        Logger orderLogger = LoggerFactory.getLogger(LoggingConstants.ORDER_LOGGER);
        String methodName = joinPoint.getSignature().getName();

        orderLogger.info(toJson("ORDER_PROCESSING", "OrderService", methodName, null, null, null));

        long startTime = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            orderLogger.info(toJson("ORDER_COMPLETED", "OrderService", methodName, duration, "SUCCESS", null));
            return result;
        } catch (Exception ex) {
            // Handled domain exceptions are WARN-logged by GlobalExceptionHandler
            logAtAppropriateLevelFor(ex, orderLogger, "ORDER_FAILED", "OrderService", methodName, null);
            throw ex;
        }
    }

    // ==================== PAYMENT - INFO (always logged) ====================

    @Around("paymentServiceMethods()")
    public Object logPayment(ProceedingJoinPoint joinPoint) throws Throwable {
        Logger paymentLogger = LoggerFactory.getLogger(LoggingConstants.PAYMENT_LOGGER);
        String methodName = joinPoint.getSignature().getName();

        paymentLogger.info(toJson("PAYMENT_PROCESSING", "PaymentService", methodName, null, null, null));

        long startTime = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            paymentLogger.info(toJson("PAYMENT_COMPLETED", "PaymentService", methodName, duration, "SUCCESS", null));
            return result;
        } catch (Exception ex) {
            // Handled domain exceptions are WARN-logged by GlobalExceptionHandler
            logAtAppropriateLevelFor(ex, paymentLogger, "PAYMENT_FAILED", "PaymentService", methodName, null);
            throw ex;
        }
    }

    // ==================== AUTH - INFO (always logged) ====================

    @Around("authServiceMethods()")
    public Object logAuth(ProceedingJoinPoint joinPoint) throws Throwable {
        Logger secLogger = LoggerFactory.getLogger(LoggingConstants.SECURITY_LOGGER);
        String methodName = joinPoint.getSignature().getName();

        secLogger.info(toJson("AUTH_ATTEMPT", "AuthService", methodName, null, null, null));

        long startTime = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            secLogger.info(toJson("AUTH_SUCCESS", "AuthService", methodName, duration, "SUCCESS", null));
            return result;
        } catch (Exception ex) {
            secLogger.warn(toJson("AUTH_FAILED", "AuthService", methodName, null, ex.getClass().getSimpleName(), ex.getMessage()));
            throw ex;
        }
    }

    // ==================== REPOSITORY - DEBUG ONLY, WARN for slow ====================

    @Around("repositoryMethods()")
    public Object logRepository(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!debugMode) return joinPoint.proceed();

        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        long startTime = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;

            if (duration > 200) {
                perfLogger.warn(toJson("SLOW_QUERY", className, methodName, duration, "SLOW", null));
            } else {
                log.trace(toJson("QUERY_COMPLETED", className, methodName, duration, "SUCCESS", null));
            }
            return result;
        } catch (Exception ex) {
            log.error(toJson("QUERY_ERROR", className, methodName, null, ex.getClass().getSimpleName(), ex.getMessage()));
            throw ex;
        }
    }

    // ==================== EXCEPTION - ERROR (always logged) ====================

    /**
     * Logs exceptions thrown from controllers or service implementations.
     *
     * <p>Expected domain exceptions ({@code BusinessException},
     * {@code ResourceNotFoundException}, {@code UnauthorizedException},
     * {@code RateLimitExceededException}, {@code FraudBlockedException}) are
     * handled by {@code GlobalExceptionHandler}, which already logs them at
     * WARN with request context — logging them here again at ERROR as
     * "UNHANDLED_EXCEPTION" is misleading noise. Only genuinely unexpected
     * exceptions are logged at ERROR.</p>
     */
    @AfterThrowing(pointcut = "controllerMethods() || serviceImplMethods()", throwing = "exception")
    public void logException(JoinPoint joinPoint, Exception exception) {
        if (isHandledDomainException(exception)) {
            return; // handled by GlobalExceptionHandler; already WARN-logged there
        }
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        log.error(toJson("UNHANDLED_EXCEPTION", className, methodName, null, exception.getClass().getSimpleName(), exception.getMessage()));
    }

    // ==================== HELPERS ====================

    /** Returns true for domain exceptions translated to 4xx by GlobalExceptionHandler. */
    private boolean isHandledDomainException(Exception ex) {
        return ex instanceof com.bhukkad.exception.BusinessException
                || ex instanceof com.bhukkad.exception.ResourceNotFoundException
                || ex instanceof com.bhukkad.exception.UnauthorizedException
                || ex instanceof com.bhukkad.exception.RateLimitExceededException
                || ex instanceof com.bhukkad.exception.FraudBlockedException
                || ex instanceof org.springframework.security.authentication.BadCredentialsException
                || ex instanceof org.springframework.security.access.AccessDeniedException;
    }

    /**
     * Logs an exception event at WARN when the exception is a handled domain
     * exception (it will be translated to a 4xx response by
     * {@code GlobalExceptionHandler}) and at ERROR otherwise.
     */
    private void logAtAppropriateLevel(Exception ex, String event, String className,
                                       String methodName, long duration) {
        if (isHandledDomainException(ex)) {
            log.warn(toJson(event, className, methodName, duration, ex.getClass().getSimpleName(), ex.getMessage()));
        } else {
            log.error(toJson(event, className, methodName, duration, ex.getClass().getSimpleName(), ex.getMessage()));
        }
    }

    /** Variant of {@link #logAtAppropriateLevel} for the dedicated ORDER/PAYMENT loggers. */
    private void logAtAppropriateLevelFor(Exception ex, Logger target, String event,
                                          String className, String methodName, Long duration) {
        if (isHandledDomainException(ex)) {
            target.warn(toJson(event, className, methodName, duration, ex.getClass().getSimpleName(), ex.getMessage()));
        } else {
            target.error(toJson(event, className, methodName, duration, ex.getClass().getSimpleName(), ex.getMessage()));
        }
    }

    private String toJson(String event, String className, String methodName,
                          Long durationMs, String status, String detail) {
        try {
            Map<String, Object> logMap = new LinkedHashMap<>();
            logMap.put("event", event);
            logMap.put("class", className);
            logMap.put("method", methodName);
            logMap.put("traceId", MDC.get(LoggingConstants.TRACE_ID));

            String userId = MDC.get(LoggingConstants.USER_ID);
            if (userId != null) logMap.put("userId", userId);

            String email = MDC.get(LoggingConstants.USER_EMAIL);
            if (email != null) logMap.put("email", email);

            if (durationMs != null) logMap.put("durationMs", durationMs);
            if (status != null) logMap.put("status", status);
            if (detail != null) logMap.put("detail", detail);

            return objectMapper.writeValueAsString(logMap);
        } catch (Exception e) {
            return String.format("{\"event\":\"%s\",\"class\":\"%s\",\"method\":\"%s\"}", event, className, methodName);
        }
    }

    private String sanitizeArgs(Object[] args) {
        if (args == null || args.length == 0) return "[]";
        return Arrays.toString(
                Arrays.stream(args)
                        .map(arg -> {
                            if (arg == null) return "null";
                            String str = arg.toString();
                            if (str.toLowerCase().contains("password") || str.toLowerCase().contains("token")) return "***";
                            if (str.length() > 200) return str.substring(0, 200) + "...";
                            return str;
                        })
                        .toArray()
        );
    }
}