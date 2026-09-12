package com.bhukkad.admin.config;

import com.bhukkad.admin.domain.event.Audited;
import com.bhukkad.admin.domain.service.AuditService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditedAspectTest {

    @Mock private AuditService auditService;
    @Mock private ProceedingJoinPoint joinPoint;
    @Mock private MethodSignature signature;

    private AuditedAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new AuditedAspect(auditService);
        when(joinPoint.getSignature()).thenReturn(signature);
    }

    /** Fixture target: real annotated methods supply real Audited instances. */
    static class AuditAnnotated {
        @Audited(action = "REFUND", resourceType = "PAYMENT",
                resourceId = "#paymentId", oldState = "'x'", newState = "#result")
        String refund(Long paymentId) {
            return "ok";
        }

        @Audited(action = "TICKET", resourceType = "SUPPORT")
        void ticket() {
        }

        @Audited(action = "BAD", resourceType = "SUPPORT", resourceId = "### not spel")
        void badExpression(Long ignored) {
        }
    }

    private Audited annotationOf(String method, Class<?>... params) throws Exception {
        Method m = AuditAnnotated.class.getDeclaredMethod(method, params);
        return m.getAnnotation(Audited.class);
    }

    private void givenCall(String method, Class<?>[] paramTypes, Object[] args) throws Exception {
        when(signature.getMethod())
                .thenReturn(AuditAnnotated.class.getDeclaredMethod(method, paramTypes));
        when(joinPoint.getArgs()).thenReturn(args);
    }

    @Test
    void audit_proceedsAndRecordsEvaluatedStates() throws Throwable {
        givenCall("refund", new Class<?>[]{Long.class}, new Object[]{77L});
        when(joinPoint.proceed()).thenReturn("REFUNDED");

        Object result = aspect.audit(joinPoint, annotationOf("refund", Long.class));

        assertThat(result).isEqualTo("REFUNDED");
        ArgumentCaptor<String> resource = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq("REFUND"), eq("PAYMENT"), resource.capture(), eq("x"), any());
        assertThat(resource.getValue()).isEqualTo("77");
    }

    @Test
    void audit_blankExpressionsRecordNullStates() throws Throwable {
        givenCall("ticket", new Class<?>[]{}, new Object[]{});
        when(joinPoint.proceed()).thenReturn(null);

        aspect.audit(joinPoint, annotationOf("ticket"));

        verify(auditService).record("TICKET", "SUPPORT", null, null, null);
    }

    @Test
    void audit_brokenSpelDegradesToNullNotFailure() throws Throwable {
        givenCall("badExpression", new Class<?>[]{Long.class}, new Object[]{1L});
        when(joinPoint.proceed()).thenReturn(null);

        assertThat(aspect.audit(joinPoint, annotationOf("badExpression", Long.class))).isNull();
        verify(auditService).record(eq("BAD"), eq("SUPPORT"), any(), any(), any());
    }

    @Test
    void audit_recordingFailureNeverBreaksAuditedMethod() throws Throwable {
        givenCall("refund", new Class<?>[]{Long.class}, new Object[]{5L});
        when(joinPoint.proceed()).thenReturn("REFUNDED");
        doThrow(new RuntimeException("audit store down"))
                .when(auditService)
                .record(any(), any(), any(), any(), any());

        Object result = aspect.audit(joinPoint, annotationOf("refund", Long.class));

        assertThat(result).isEqualTo("REFUNDED"); // swallowed
    }

    @Test
    void audit_positionalVariablesAvailableToSpel() throws Throwable {
        givenCall("refund", new Class<?>[]{Long.class}, new Object[]{9L});
        when(joinPoint.proceed()).thenReturn("done");

        Audited annotation = org.mockito.Mockito.mock(Audited.class);
        when(annotation.action()).thenReturn("POS");
        when(annotation.resourceType()).thenReturn("USER");
        when(annotation.resourceId()).thenReturn("#p0");
        when(annotation.oldState()).thenReturn("");
        when(annotation.newState()).thenReturn("#a0");

        aspect.audit(joinPoint, annotation);

        verify(auditService).record("POS", "USER", "9", null, "9");
    }
}
