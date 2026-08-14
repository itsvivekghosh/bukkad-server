package com.bhukkad.ratelimit;

import com.bhukkad.dto.request.LoginRequest;
import com.bhukkad.exception.RateLimitExceededException;
import com.bhukkad.security.SecurityUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitAspectTest {

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private RateLimitAspect rateLimitAspect;

    @Test
    void enforceRateLimit_allowsWhenUnderLimit() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint("trackOrder", 11L);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(rateLimitService.check(eq("order-track"), eq("user:5:order:11")))
                .thenReturn(RateLimitDecision.allowed(1, 20, 60));
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", rateLimitAspect.enforceRateLimit(joinPoint, rateLimited("order-track")));
    }

    @Test
    void enforceRateLimit_throwsWhenExceeded() throws Throwable {
        ProceedingJoinPoint joinPoint = mockKitchenJoinPoint(10L);
        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        when(rateLimitService.check(eq("kitchen-queue"), eq("user:7:restaurant:10")))
                .thenReturn(RateLimitDecision.denied(31, 30, 25));

        RateLimitExceededException ex = assertThrows(RateLimitExceededException.class,
                () -> rateLimitAspect.enforceRateLimit(joinPoint, rateLimited("kitchen-queue")));

        assertEquals(25, ex.getRetryAfterSeconds());
        verify(joinPoint, never()).proceed();
    }

    @Test
    void enforceRateLimit_authLogin_doesNotRequireAuthenticatedUser() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("admin@bhukkad.dev");
        loginRequest.setPassword("secret");

        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(SampleController.class.getMethod("login", LoginRequest.class));
        when(joinPoint.getArgs()).thenReturn(new Object[]{loginRequest});
        when(rateLimitService.check(eq("auth-login"), eq("login:admin@bhukkad.dev")))
                .thenReturn(RateLimitDecision.allowed(1, 10, 60));
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", rateLimitAspect.enforceRateLimit(joinPoint, rateLimited("auth-login")));
        verify(securityUtils, never()).getCurrentUserId();
    }

    private RateLimited rateLimited(String bucket) {
        return new RateLimited() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return RateLimited.class;
            }

            @Override
            public String value() {
                return bucket;
            }
        };
    }

    private ProceedingJoinPoint mockJoinPoint(String methodName, Long pathId) throws NoSuchMethodException {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(SampleController.class.getMethod(methodName, Long.class, int.class));
        when(joinPoint.getArgs()).thenReturn(new Object[]{pathId, 50});
        return joinPoint;
    }

    private ProceedingJoinPoint mockKitchenJoinPoint(Long restaurantId) throws NoSuchMethodException {
        return mockJoinPoint("getKitchenQueue", restaurantId);
    }

    static class SampleController {
        public void trackOrder(Long orderId, int ignored) {}

        public void getKitchenQueue(Long restaurantId, int limit) {}

        public void login(LoginRequest request) {}
    }
}
