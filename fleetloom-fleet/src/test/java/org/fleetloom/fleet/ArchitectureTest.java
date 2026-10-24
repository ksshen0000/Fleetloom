package org.fleetloom.fleet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "org.fleetloom.fleet", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule coreIsFrameworkFree = noClasses()
            .that().resideInAPackage("..fleet.core..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                    "org.fleetloom.fleet.app..", "org.fleetloom.fleet.api..", "org.fleetloom.fleet.infra..");

    @ArchTest
    static final ArchRule appDoesNotDependOnAdapters = noClasses()
            .that().resideInAPackage("..fleet.app..")
            .should().dependOnClassesThat().resideInAnyPackage("org.fleetloom.fleet.api..", "org.fleetloom.fleet.infra..",
                    "org.springframework.data..", "org.springframework.web..");
}
