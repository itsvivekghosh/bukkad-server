package com.bhukkad.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditedAspectTest {

    @Mock
    private AuditService auditService;
    @Mock
    private ProceedingJoinPoint joinPoint;
    @Mock
    private MethodSignature signature;

    private AuditedAspect aspect;

    private static class Sample {
        @Audited(action = "CREATE", resourceType = "ORDER", resourceId = "#p0",
                oldState = "#p1", newState = "#p2")
        public String create(String orderId, String oldState, String newState) {
            return "created-" + orderId;
        }

        @Audited(action = "UPDATE", resourceType = "ORDER", resourceId = "#orderId",
                oldState = "", newState = "  ")
        public String update(String orderId) {
            return "updated";
        }

        @Audited(action = "FAIL", resourceType = "ORDER", resourceId = "#p0",
                oldState = "#bogus.expression", newState = "#p1")
        public String fail(String orderId, String state) {
            return "ok";
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        aspect = new AuditedAspect(auditService);
        lenient().when(joinPoint.getSignature()).thenReturn(signature);
        lenient().when(signature.getMethod()).thenReturn(Sample.class.getMethod("create", String.class, String.class, String.class));
        lenient().when(signature.getDeclaringType()).thenReturn(Sample.class);
    }

    @Test
    void audit_recordsEventAfterSuccessfulProceed() throws Throwable {
        when(joinPoint.proceed()).thenReturn("created-abc");
        when(joinPoint.getArgs()).thenReturn(new Object[]{"abc", "PENDING", "CONFIRMED"});

        Object result = aspect.audit(joinPoint, Sample.class.getMethod("create", String.class, String.class, String.class).getAnnotation(Audited.class));

        assertEquals("created-abc", result);
        verify(auditService).record(eq("CREATE"), eq("ORDER"), eq("abc"), eq("PENDING"), eq("CONFIRMED"));
    }

    @Test
    void audit_propagatesTargetException_withoutAuditing() throws Throwable {
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> aspect.audit(
                joinPoint, Sample.class.getMethod("create", String.class, String.class, String.class).getAnnotation(Audited.class)));

        verify(auditService, never()).record(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void audit_blankExpressions_recordNulls() throws Throwable {
        when(joinPoint.proceed()).thenReturn("updated");
        when(joinPoint.getArgs()).thenReturn(new Object[]{"ord-1"});
        when(signature.getMethod()).thenReturn(Sample.class.getMethod("update", String.class));

        aspect.audit(joinPoint, Sample.class.getMethod("update", String.class).getAnnotation(Audited.class));

        verify(auditService).record(eq("UPDATE"), eq("ORDER"), eq("ord-1"), isNull(), isNull());
    }

    @Test
    void audit_spelEvaluationFailure_recordsNullAndSwallows() throws Throwable {
        when(joinPoint.proceed()).thenReturn("ok");
        when(joinPoint.getArgs()).thenReturn(new Object[]{"ord-2", "state"});
        when(signature.getMethod()).thenReturn(Sample.class.getMethod("fail", String.class, String.class));

        Object result = aspect.audit(joinPoint, Sample.class.getMethod("fail", String.class, String.class).getAnnotation(Audited.class));

        assertEquals("ok", result);
        // oldState expression is invalid -> null; resourceId resolves normally
        verify(auditService).record(eq("FAIL"), eq("ORDER"), eq("ord-2"), isNull(), eq("state"));
    }

    @Test
    void audit_auditServiceFailure_swallowedResultReturned() throws Throwable {
        when(joinPoint.proceed()).thenReturn("created-abc");
        when(joinPoint.getArgs()).thenReturn(new Object[]{"abc", "PENDING", "CONFIRMED"});
        doThrow(new RuntimeException("audit db down")).when(auditService).record(anyString(), anyString(), anyString(), anyString(), anyString());

        Object result = aspect.audit(joinPoint, Sample.class.getMethod("create", String.class, String.class, String.class).getAnnotation(Audited.class));

        assertEquals("created-abc", result);
    }

    @Test
    void audit_usesPIndexVariables_whenParameterNamesMissing() throws Throwable {
        when(joinPoint.proceed()).thenReturn("created-xyz");
        when(joinPoint.getArgs()).thenReturn(new Object[]{"xyz", "A", "B"});
        // Parameter names may be unavailable at runtime; the aspect still binds
        // p0/a0 aliases in buildContext(), so the SpEL #p0 expression resolves.
        aspect.audit(joinPoint, Sample.class.getMethod("create", String.class, String.class, String.class).getAnnotation(Audited.class));

        verify(auditService).record(eq("CREATE"), eq("ORDER"), eq("xyz"), any(), any());
    }
}