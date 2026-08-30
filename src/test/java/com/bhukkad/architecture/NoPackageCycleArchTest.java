package com.bhukkad.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

/**
 * Package-cycle guardrail for the microservice extraction (Phase 1).
 *
 * <p>The package graph measured today contains ten direct cycles that block a
 * compile-enforced multi-module build ({@code config ↔ security ↔ audit/log/live},
 * {@code event ↔ outbox}, {@code payment ↔ serviceImpl ↔ wallet},
 * {@code config ↔ apikey/payment}). This test <strong>freezes</strong> every
 * direction of every known cycle edge: the existing violations live in the
 * versioned ArchUnit store and are tolerated, while any NEW dependency along
 * a forbidden edge — or any NEW cycle anywhere — fails the build. The tangle
 * can therefore only shrink as the runbook breaks are landed, never grow.</p>
 *
 * <p>Two cycles were already broken and are NOT frozen — they must stay
 * clean: {@code dto.response → outbox} and {@code mapper → storage}.</p>
 */
class NoPackageCycleArchTest {

    private static JavaClasses classes;

    /** Known cycle edges (both directions). Each becomes a frozen rule. */
    private static final List<String[]> CYCLE_EDGES = List.of(
            new String[]{"config", "security"},
            new String[]{"config", "apikey"},
            new String[]{"config", "payment"},
            new String[]{"security", "audit"},
            new String[]{"security", "logging"},
            new String[]{"security", "live"},
            new String[]{"event", "outbox"},
            new String[]{"event", "live"},
            new String[]{"payment", "serviceImpl"},
            new String[]{"payment", "wallet"}
    );

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhukkad");
    }

    @Test
    void frozenCycleEdgesMustNotGrow() {
        for (String[] edge : CYCLE_EDGES) {
            String from = edge[0];
            String to = edge[1];
            ArchRule rule = noClasses()
                    .that().resideInAPackage("com.bhukkad." + from + "..")
                    .should().dependOnClassesThat().resideInAPackage("com.bhukkad." + to + "..")
                    .because("a package cycle was measured between '" + from + "' and '" + to
                            + "'; breaking it is required before the multi-module split "
                            + "(modularization runbook) and the edge must not gain new dependencies");
            freeze(rule).check(classes);
        }
    }

    /** Already-broken edges: enforced strictly, no freeze. */
    @Test
    void dtoLayerMustNotDependOnOutbox() {
        noClasses().that().resideInAPackage("com.bhukkad.dto..")
                .should().dependOnClassesThat().resideInAPackage("com.bhukkad.outbox..")
                .because("DTOs are pure data carriers across all future services; " +
                        "entity mapping belongs in the owning module (DeadLetterEventResponseMapper)")
                .check(classes);
    }

    /** Already-broken edges: enforced strictly, no freeze. */
    @Test
    void mapperLayerMustNotDependOnStorage() {
        noClasses().that().resideInAPackage("com.bhukkad.mapper..")
                .should().dependOnClassesThat().resideInAPackage("com.bhukkad.storage..")
                .because("mappers depend on the ImageUrlResolver abstraction; storage provides the implementation")
                .check(classes);
    }
}
