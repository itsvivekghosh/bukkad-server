package com.bhukkad.datasource;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadReplicaAspectTest {

    @InjectMocks
    private ReadReplicaAspect aspect;

    @AfterEach
    void tearDown() {
        ReadReplicaContext.clear();
    }

    @Test
    void routesReadOnlyTransactionalMethodsToReplica() throws Throwable {
        ReadOnlyService service = new ReadOnlyService();
        ProceedingJoinPoint joinPoint = mockJoinPoint(service, "read");

        when(joinPoint.proceed()).thenAnswer(invocation -> {
            assertEquals(ReadReplicaType.REPLICA, ReadReplicaContext.get());
            return "ok";
        });

        assertEquals("ok", aspect.routeDataSource(joinPoint));
        assertEquals(ReadReplicaType.PRIMARY, ReadReplicaContext.get());
    }

    @Test
    void routesWriteTransactionalMethodsToPrimary() throws Throwable {
        WriteService service = new WriteService();
        ProceedingJoinPoint joinPoint = mockJoinPoint(service, "write");

        when(joinPoint.proceed()).thenAnswer(invocation -> {
            assertEquals(ReadReplicaType.PRIMARY, ReadReplicaContext.get());
            return "written";
        });

        assertEquals("written", aspect.routeDataSource(joinPoint));
    }

    @Test
    void routesUseReadReplicaMethodsToReplica() throws Throwable {
        ReportService service = new ReportService();
        ProceedingJoinPoint joinPoint = mockJoinPoint(service, "list");

        when(joinPoint.proceed()).thenAnswer(invocation -> {
            assertEquals(ReadReplicaType.REPLICA, ReadReplicaContext.get());
            return "listed";
        });

        assertEquals("listed", aspect.routeDataSource(joinPoint));
    }

    private ProceedingJoinPoint mockJoinPoint(Object target, String methodName) throws NoSuchMethodException {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(target.getClass().getMethod(methodName));
        when(joinPoint.getTarget()).thenReturn(target);
        return joinPoint;
    }

    static class ReadOnlyService {
        @Transactional(readOnly = true)
        public void read() {}
    }

    static class WriteService {
        @Transactional
        public void write() {}
    }

    static class ReportService {
        @UseReadReplica
        public void list() {}
    }
}
