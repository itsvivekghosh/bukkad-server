package com.bhukkad.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Records an {@link Audited} audit event after the annotated method returns normally.
 *
 * <p>Exceptions thrown by the method itself propagate untouched; only failures in audit recording
 * itself are swallowed so auditing never breaks the business operation.</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditedAspect {

    private final AuditService auditService;

    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    private final SpelExpressionParser spelExpressionParser = new SpelExpressionParser();

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        Object result = joinPoint.proceed();

        try {
            StandardEvaluationContext context = buildContext(joinPoint);
            auditService.record(
                    audited.action(),
                    audited.resourceType(),
                    evaluate(audited.resourceId(), context),
                    evaluate(audited.oldState(), context),
                    evaluate(audited.newState(), context));
        } catch (Exception ex) {
            log.warn("Failed to record audit event for {}#{}",
                    joinPoint.getSignature().getDeclaringType().getSimpleName(),
                    joinPoint.getSignature().getName(), ex);
        }

        return result;
    }

    private StandardEvaluationContext buildContext(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);

        StandardEvaluationContext context = new StandardEvaluationContext();
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length && i < args.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }
        for (int i = 0; i < args.length; i++) {
            context.setVariable("p" + i, args[i]);
            context.setVariable("a" + i, args[i]);
        }
        return context;
    }

    private String evaluate(String expression, StandardEvaluationContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            Object value = spelExpressionParser.parseExpression(expression).getValue(context);
            return value == null ? null : value.toString();
        } catch (Exception ex) {
            log.warn("Audit SpEL evaluation failed for expression '{}'", expression, ex);
            return null;
        }
    }
}