package com.bhukkad.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoggingAspectTest {

    private LoggingAspect aspect;
    private ProceedingJoinPoint joinPoint;

    @BeforeEach
    void setUp() {
        aspect = new LoggingAspect();
        joinPoint = mockJoinPoint("Controller", "create");
        MDC.put(LoggingConstants.TRACE_ID, "trace");
        MDC.put(LoggingConstants.USER_ID, "1");
        MDC.put(LoggingConstants.USER_EMAIL, "a@b.com");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void pointcuts_areInvocable() {
        aspect.controllerMethods();
        aspect.serviceImplMethods();
        aspect.repositoryMethods();
        aspect.orderServiceMethods();
        aspect.paymentServiceMethods();
        aspect.authServiceMethods();
    }

    @Test
    void logController_debugOn_successAndError() throws Throwable {
        ReflectionTestUtils.setField(aspect, "debugMode", true);
        when(joinPoint.proceed()).thenReturn("ok");
        when(joinPoint.getArgs()).thenReturn(new Object[]{"password=secret", "token=abc", "x".repeat(250), null, "normal"});

        assertEquals("ok", aspect.logController(joinPoint));

        when(joinPoint.proceed()).thenThrow(new IllegalStateException("fail"));
        assertThrows(IllegalStateException.class, () -> aspect.logController(joinPoint));
    }

    @Test
    void logController_debugOff_stillLogsError() throws Throwable {
        ReflectionTestUtils.setField(aspect, "debugMode", false);
        when(joinPoint.proceed()).thenReturn("ok");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        assertEquals("ok", aspect.logController(joinPoint));

        when(joinPoint.proceed()).thenThrow(new RuntimeException("boom"));
        assertThrows(RuntimeException.class, () -> aspect.logController(joinPoint));
    }

    @Test
    void logService_coversSlowFastAndError() throws Throwable {
        ReflectionTestUtils.setField(aspect, "debugMode", true);
        when(joinPoint.proceed()).thenReturn("ok");
        assertEquals("ok", aspect.logService(joinPoint));

        when(joinPoint.proceed()).thenAnswer(inv -> {
            Thread.sleep(510);
            return "slow";
        });
        assertEquals("slow", aspect.logService(joinPoint));

        ReflectionTestUtils.setField(aspect, "debugMode", false);
        when(joinPoint.proceed()).thenReturn("fast");
        assertEquals("fast", aspect.logService(joinPoint));

        when(joinPoint.proceed()).thenThrow(new RuntimeException("svc"));
        assertThrows(RuntimeException.class, () -> aspect.logService(joinPoint));
    }

    @Test
    void logOrderPaymentAuth_successAndFailure() throws Throwable {
        when(joinPoint.proceed()).thenReturn("ok");
        assertEquals("ok", aspect.logOrder(joinPoint));
        assertEquals("ok", aspect.logPayment(joinPoint));
        assertEquals("ok", aspect.logAuth(joinPoint));

        when(joinPoint.proceed()).thenThrow(new RuntimeException("fail"));
        assertThrows(RuntimeException.class, () -> aspect.logOrder(joinPoint));
        assertThrows(RuntimeException.class, () -> aspect.logPayment(joinPoint));
        assertThrows(RuntimeException.class, () -> aspect.logAuth(joinPoint));
    }

    @Test
    void logRepository_debugOff_justProceeds() throws Throwable {
        ReflectionTestUtils.setField(aspect, "debugMode", false);
        when(joinPoint.proceed()).thenReturn("ok");
        assertEquals("ok", aspect.logRepository(joinPoint));
    }

    @Test
    void logRepository_debugOn_coversFastSlowAndError() throws Throwable {
        ReflectionTestUtils.setField(aspect, "debugMode", true);
        when(joinPoint.proceed()).thenReturn("ok");
        assertEquals("ok", aspect.logRepository(joinPoint));

        when(joinPoint.proceed()).thenAnswer(inv -> {
            Thread.sleep(210);
            return "slow";
        });
        assertEquals("slow", aspect.logRepository(joinPoint));

        when(joinPoint.proceed()).thenThrow(new RuntimeException("query"));
        assertThrows(RuntimeException.class, () -> aspect.logRepository(joinPoint));
    }

    @Test
    void logException_writesError() {
        JoinPoint point = mock(JoinPoint.class);
        Signature signature = mock(Signature.class);
        when(point.getTarget()).thenReturn(this);
        when(point.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("method");

        aspect.logException(point, new RuntimeException("unhandled"));
    }

    @Test
    void toJson_fallsBackWhenMapperFails() throws Exception {
        ObjectMapper failing = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("fail") {};
            }
        };
        ReflectionTestUtils.setField(aspect, "objectMapper", failing);

        String json = (String) ReflectionTestUtils.invokeMethod(
                aspect, "toJson", "EVENT", "Cls", "method", 10L, "OK", "detail");
        assertTrue(json.contains("EVENT"));
        assertTrue(json.contains("Cls"));
    }

    @Test
    void toJson_omitsOptionalFieldsWhenAbsent() {
        MDC.clear();
        String json = (String) ReflectionTestUtils.invokeMethod(
                aspect, "toJson", "EVENT", "Cls", "method", null, null, null);
        assertTrue(json.contains("EVENT"));
        assertTrue(!json.contains("userId"));
        assertTrue(!json.contains("durationMs"));
    }

    @Test
    void sanitizeArgs_handlesNullAndEmptyArray() throws Exception {
        java.lang.reflect.Method method = LoggingAspect.class.getDeclaredMethod("sanitizeArgs", Object[].class);
        method.setAccessible(true);
        assertEquals("[]", method.invoke(aspect, new Object[]{null}));
        assertEquals("[]", method.invoke(aspect, (Object) new Object[0]));
    }

    private ProceedingJoinPoint mockJoinPoint(String className, String method) {
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(point.getTarget()).thenReturn(new DummyTarget());
        when(point.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn(method);
        return point;
    }

    private static class DummyTarget {
    }
}
