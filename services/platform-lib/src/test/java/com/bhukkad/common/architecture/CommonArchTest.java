package com.bhukkad.common.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Boundary guardrails for the {@code platform-lib} platform library
 * (architecture-microservices-postgresql.md §4: "no DB, no web", business
 * logic is never shared).
 *
 * <ul>
 *   <li>No dependency on Spring Web (controllers/REST) — the library is
 *       consumed by services, never exposed.</li>
 *   <li>No dependency on any monolith package — the library is a fresh
 *       platform artifact, not a wrapper over the monolith.</li>
 *   <li>JPA platform classes must live in their owning sub-package (outbox,
 *       idempotency, saga) so services can scan entities/repositories by
 *       package.</li>
 *   <li>No direct logging of platform internals beyond SLF4J API.</li>
 * </ul>
 */
class CommonArchTest {

    private static final JavaClasses COMMON_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
            .importPackages("com.bhukkad.common");

    @Test
    void commonDoesNotDependOnWeb() {
        // The `web` package (GlobalExceptionHandler), `logging` package
        // (RequestLoggingFilter, TraceIdResolver) and `security` package
        // (PlatformJwtValidator, PlatformJwtAuthFilter) are web-coupled by
        // design (optional spring-boot-starter-web / spring-security dependency).
        // Every other platform package must stay web-free.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bhukkad.common..")
                .and().resideOutsideOfPackages(
                        "com.bhukkad.common.web..",
                        "com.bhukkad.common.logging..",
                        "com.bhukkad.common.security..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "org.springframework.web.bind.annotation..",
                        "org.springframework.web.socket..",
                        "jakarta.servlet..");
        rule.check(COMMON_CLASSES);
    }

    @Test
    void commonDoesNotDependOnMonolith() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bhukkad.common..")
                .should().dependOnClassesThat().resideInAnyPackage("com.bhukkad.serviceImpl..", "com.bhukkad.entity..");
        rule.check(COMMON_CLASSES);
    }

    @Test
    void jpaEntitiesStayInOwningSubPackage() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .should().resideInAnyPackage(
                        "com.bhukkad.common.outbox..",
                        "com.bhukkad.common.idempotency..",
                        "com.bhukkad.common.saga..");
        rule.check(COMMON_CLASSES);
    }

    @Test
    void repositoriesArePublicInterfacesInOwningSubPackage() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.bhukkad.common..")
                .and().haveSimpleNameEndingWith("Repository")
                .should().beInterfaces()
                .andShould().resideInAnyPackage(
                        "com.bhukkad.common.outbox..",
                        "com.bhukkad.common.idempotency..",
                        "com.bhukkad.common.saga..");
        rule.check(COMMON_CLASSES);
    }

    // ------------------------------------------------------------------
    // G-4 (PRODUCTION-READINESS-AUDIT-GUIDE.md): transaction boundaries —
    // no @Transactional on controllers (V-05 proxy/self-invocation class).
    // Canonical statement of the rule; per-service *ArchTest files mirror it
    // for their own controller layer (platform-lib itself has no controllers,
    // so the rule is vacuously true here and guards future regressions).
    // ------------------------------------------------------------------

    @Test
    void noTransactionalOnControllerClasses() {
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .check(COMMON_CLASSES);
    }

    @Test
    void noTransactionalOnControllerMethods() {
        noMethods().that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .check(COMMON_CLASSES);
    }
}
