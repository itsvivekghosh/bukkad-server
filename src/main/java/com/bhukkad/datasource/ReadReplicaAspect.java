package com.bhukkad.datasource;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReadReplicaAspect {

    @Around("@annotation(com.bhukkad.datasource.UseReadReplica) || " +
            "@within(com.bhukkad.datasource.UseReadReplica) || " +
            "@annotation(org.springframework.transaction.annotation.Transactional) || " +
            "@within(org.springframework.transaction.annotation.Transactional)")
    public Object routeDataSource(ProceedingJoinPoint joinPoint) throws Throwable {
        ReadReplicaType previous = ReadReplicaContext.get();
        ReadReplicaType target = resolveTarget(joinPoint);
        ReadReplicaContext.set(target);
        if (log.isTraceEnabled()) {
            log.trace("READ_REPLICA_ROUTE | {} | {} -> {}",
                    joinPoint.getSignature().toShortString(), previous, target);
        }
        try {
            return joinPoint.proceed();
        } finally {
            ReadReplicaContext.set(previous);
        }
    }

    private ReadReplicaType resolveTarget(ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Class<?> targetClass = joinPoint.getTarget().getClass();

        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
        if (transactional == null) {
            transactional = AnnotatedElementUtils.findMergedAnnotation(targetClass, Transactional.class);
        }

        if (transactional != null && !transactional.readOnly()) {
            return ReadReplicaType.PRIMARY;
        }

        if (AnnotatedElementUtils.hasAnnotation(method, UseReadReplica.class)
                || AnnotatedElementUtils.hasAnnotation(targetClass, UseReadReplica.class)
                || (transactional != null && transactional.readOnly())) {
            return ReadReplicaType.REPLICA;
        }

        return ReadReplicaType.PRIMARY;
    }
}
