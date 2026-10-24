package org.fleetloom.resource;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces the layering described in docs/requirements.md section 14.2:
 * api -> application -> domain, with infrastructure implementing domain ports.
 */
@AnalyzeClasses(packages = "org.fleetloom.resource", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "com.baomidou..", "org.apache.ibatis..", "tools.jackson..",
                    "com.fasterxml.jackson..", "jakarta..")
            .because("domain logic must stay framework-free and unit-testable");

    @ArchTest
    static final ArchRule layers = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Api").definedBy("..api..")
            .layer("Application").definedBy("..application..")
            .layer("Domain").definedBy("..domain..")
            .layer("Infrastructure").definedBy("..infrastructure..")
            .whereLayer("Api").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Api")
            .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer();
}
