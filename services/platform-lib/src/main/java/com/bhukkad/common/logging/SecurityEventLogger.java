package com.bhukkad.common.logging;

import com.bhukkad.common.tracing.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;

@Component
public class SecurityEventLogger {

    private static final Logger securityLogger = LoggerFactory.getLogger(LoggingConstants.SECURITY_LOGGER);
    private static final Logger log = LoggerFactory.getLogger(SecurityEventLogger.class);

    public void logLoginSuccess(Long userId, String email, String role) {
        securityLogger.info(
                "[{}] [SUCCESS] [{}] UserId: {} | Email: {} | Role: {} | IP: {} | TraceId: {}",
                Instant.now(),
                LoggingConstants.EVENT_USER_LOGIN,
                userId,
                maskEmail(email),
                role,
                getClientIp(),
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logLoginFailure(String email, String reason) {
        securityLogger.warn(
                "[{}] [FAILED] [{}] Email: {} | Reason: {} | IP: {} | TraceId: {}",
                Instant.now(),
                LoggingConstants.EVENT_USER_LOGIN,
                maskEmail(email),
                reason,
                getClientIp(),
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logRegistration(Long userId, String email, String role) {
        securityLogger.info(
                "[{}] [SUCCESS] [{}] UserId: {} | Email: {} | Role: {} | IP: {} | TraceId: {}",
                Instant.now(),
                LoggingConstants.EVENT_USER_REGISTER,
                userId,
                maskEmail(email),
                role,
                getClientIp(),
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logUnauthorizedAccess(String email, String endpoint) {
        securityLogger.warn(
                "[{}] [WARNING] [{}] Email: {} | Endpoint: {} | IP: {} | TraceId: {}",
                Instant.now(),
                LoggingConstants.EVENT_UNAUTHORIZED_ACCESS,
                maskEmail(email),
                endpoint,
                getClientIp(),
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logInvalidToken(String reason) {
        securityLogger.warn(
                "[{}] [WARNING] [{}] Reason: {} | IP: {} | TraceId: {}",
                Instant.now(),
                LoggingConstants.EVENT_INVALID_TOKEN,
                reason,
                getClientIp(),
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logPasswordChange(Long userId, String email) {
        securityLogger.info(
                "[{}] [SUCCESS] [PASSWORD_CHANGE] UserId: {} | Email: {} | IP: {} | TraceId: {}",
                Instant.now(),
                userId,
                maskEmail(email),
                getClientIp(),
                MDC.get(LoggingConstants.TRACE_ID)
        );
    }

    public void logRateLimitExceeded(String bucket, String clientIp, int limit, int retryAfterSeconds) {
        securityLogger.warn(
                "[{}] [RATE_LIMIT] [RATE_LIMIT_EXCEEDED] Bucket: {} | IP: {} | Limit: {} | RetryAfter: {} | TraceId: {}",
                Instant.now(),
                bucket,
                maskIp(clientIp),
                limit,
                retryAfterSeconds,
                TraceContext.currentTraceId()
        );
    }

    public void logAuthFailure(String reason, String ipAddress, String userAgent) {
        securityLogger.warn(
                "[{}] [FAILED] [AUTH_FAILURE] Reason: {} | IP: {} | UserAgent: {} | TraceId: {}",
                Instant.now(),
                reason,
                maskIp(ipAddress),
                maskUserAgent(userAgent),
                TraceContext.currentTraceId()
        );
    }

    public void logInvalidToken(String reason, String ipAddress) {
        securityLogger.warn(
                "[{}] [WARNING] [INVALID_TOKEN] Reason: {} | IP: {} | TraceId: {}",
                Instant.now(),
                reason,
                maskIp(ipAddress),
                TraceContext.currentTraceId()
        );
    }

    public void logAccessDenied(String userId, String path, String requiredRole) {
        securityLogger.warn(
                "[{}] [WARNING] [ACCESS_DENIED] UserId: {} | Path: {} | RequiredRole: {} | TraceId: {}",
                Instant.now(),
                userId,
                path,
                requiredRole,
                TraceContext.currentTraceId()
        );
    }

    public void logAccountLockout(String userId, String ipAddress, int failedAttempts) {
        securityLogger.warn(
                "[{}] [WARNING] [ACCOUNT_LOCKOUT] UserId: {} | IP: {} | FailedAttempts: {} | TraceId: {}",
                Instant.now(),
                userId,
                maskIp(ipAddress),
                failedAttempts,
                TraceContext.currentTraceId()
        );
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        String[] parts = email.split("@");
        String local = parts[0];
        String domain = parts[1];
        if (local.length() <= 2) return "**@" + domain;
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + "@" + domain;
    }

    private String getClientIp() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String xForwardedFor = request.getHeader("X-Forwarded-For");
                if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                    return xForwardedFor.split(",")[0].trim();
                }
                return request.getRemoteAddr();
            }
        } catch (Exception e) {
            log.debug("Could not get client IP: {}", e.getMessage());
        }
        return "unknown";
    }

    private String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        if (ip.contains(".")) {
            String[] parts = ip.split("\\.");
            if (parts.length == 4) {
                return parts[0] + "." + parts[1] + "." + parts[2] + ".xxx";
            }
        }
        return ip;
    }

    private String maskUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "unknown";
        }
        if (userAgent.length() > 100) {
            return userAgent.substring(0, 100) + "...";
        }
        return userAgent;
    }
}