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

    @Mock
    private UserTierResolver userTierResolver;

    @InjectMocks
    private RateLimitAspect rateLimitAspect;

    @Test
    void enforceRateLimit_allowsWhenUnderLimit() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint("trackOrder", 11L);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(userTierResolver.resolveCurrentTier()).thenReturn("free");
        when(rateLimitService.check(eq("order-track"), eq("user:5:order:11"), eq("free")))
                .thenReturn(RateLimitDecision.allowed(1, 20, 60));
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", rateLimitAspect.enforceRateLimit(joinPoint, rateLimited("order-track")));
    }

    @Test
    void enforceRateLimit_throwsWhenExceeded() throws Throwable {
        ProceedingJoinPoint joinPoint = mockKitchenJoinPoint(10L);
        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        when(userTierResolver.resolveCurrentTier()).thenReturn("free");
        when(rateLimitService.check(eq("kitchen-queue"), eq("user:7:restaurant:10"), eq("free")))
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
        when(userTierResolver.resolveCurrentTier()).thenReturn("free");
        when(rateLimitService.check(eq("auth-login"), eq("login:admin@bhukkad.dev"), eq("free")))
                .thenReturn(RateLimitDecision.allowed(1, 10, 60));
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", rateLimitAspect.enforceRateLimit(joinPoint, rateLimited("auth-login")));
        verify(securityUtils, never()).getCurrentUserId();
    }

    @Test
    void enforceRateLimit_authLogin_blankEmail_usesUnknown() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("   ");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(SampleController.class.getMethod("login", LoginRequest.class));
        when(joinPoint.getArgs()).thenReturn(new Object[]{loginRequest});
        when(userTierResolver.resolveCurrentTier()).thenReturn("free");
        when(rateLimitService.check(eq("auth-login"), eq("login:unknown"), eq("free")))
                .thenReturn(RateLimitDecision.allowed(1, 10, 60));
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", rateLimitAspect.enforceRateLimit(joinPoint, rateLimited("auth-login")));
    }

    @Test
    void enforceRateLimit_search_withKeyword_andAnonymousUser() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(SampleController.class.getMethod("search", String.class, int.class));
        when(joinPoint.getArgs()).thenReturn(new Object[]{"Biryani", 10});
        when(securityUtils.getCurrentUserId()).thenThrow(new com.bhukkad.exception.UnauthorizedException("no auth"));
        when(userTierResolver.resolveCurrentTier()).thenReturn("free");
        when(rateLimitService.check(eq("search"), eq("search:biryani:user:anonymous"), eq("free")))
                .thenReturn(RateLimitDecision.allowed(1, 30, 60));
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", rateLimitAspect.enforceRateLimit(joinPoint, rateLimited("search")));
    }

    @Test
    void enforceRateLimit_search_blankKeyword_usesAll() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(SampleController.class.getMethod("search", String.class, int.class));
        when(joinPoint.getArgs()).thenReturn(new Object[]{"", 10});
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(userTierResolver.resolveCurrentTier()).thenReturn("free");
        when(rateLimitService.check(eq("search"), eq("search:all:user:5"), eq("free")))
                .thenReturn(RateLimitDecision.allowed(1, 30, 60));
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", rateLimitAspect.enforceRateLimit(joinPoint, rateLimited("search")));
    }

    @Test
    void enforceRateLimit_cartMutation_bucket() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(SampleController.class.getMethod("addToCart", Long.class, int.class));
        when(joinPoint.getArgs()).thenReturn(new Object[]{100L, 2});
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(userTierResolver.resolveCurrentTier()).thenReturn("free");
        when(rateLimitService.check(eq("cart-mutation"), eq("user:5:cart"), eq("free")))
                .thenReturn(RateLimitDecision.allowed(1, 20, 60));
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", rateLimitAspect.enforceRateLimit(joinPoint, rateLimited("cart-mutation")));
    }

    @Test
    void enforceRateLimit_defaultBucket_usesUserId() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(SampleController.class.getMethod("trackOrder", Long.class, int.class));
        when(joinPoint.getArgs()).thenReturn(new Object[]{100L, 50});
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(userTierResolver.resolveCurrentTier()).thenReturn("premium");
        when(rateLimitService.check(eq("some-other-bucket"), eq("user:5"), eq("premium")))
                .thenReturn(RateLimitDecision.allowed(1, 20, 60));
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", rateLimitAspect.enforceRateLimit(joinPoint, rateLimited("some-other-bucket")));
    }

    @Test
    void enforceRateLimit_orderTrack_unnamedParam_fallsBackToFirstLong() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(SampleController.class.getMethod("unnamedParams", Long.class, Long.class));
        when(joinPoint.getArgs()).thenReturn(new Object[]{11L, 99L});
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(userTierResolver.resolveCurrentTier()).thenReturn("free");
        when(rateLimitService.check(eq("order-track"), eq("user:5:order:11"), eq("free")))
                .thenReturn(RateLimitDecision.allowed(1, 20, 60));
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", rateLimitAspect.enforceRateLimit(joinPoint, rateLimited("order-track")));
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
        // Intentionally empty: these methods are only invocation targets for
        // the rate-limit aspect tests — the aspect matches on annotations and
        // parameter shapes, never on method bodies.
        public void trackOrder(Long orderId, int ignored) {
            // no-op: aspect test target.
        }

        public void getKitchenQueue(Long restaurantId, int limit) {
            // no-op: aspect test target.
        }

        public void login(LoginRequest request) {
            // no-op: aspect test target.
        }

        public void search(String keyword, int limit) {
            // no-op: aspect test target.
        }

        public void addToCart(Long menuItemId, int quantity) {
            // no-op: aspect test target.
        }

        public void unnamedParams(Long a, Long b) {
            // no-op: aspect test target.
        }
    }
}
