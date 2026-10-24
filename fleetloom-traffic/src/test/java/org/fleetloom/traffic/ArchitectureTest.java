package org.fleetloom.traffic;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "org.fleetloom.traffic", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule coreIsFrameworkFree = noClasses()
            .that().resideInAPackage("..traffic.core..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                    "org.fleetloom.traffic.app..", "org.fleetloom.traffic.api..", "org.fleetloom.traffic.infra..")
            .because("planning and reservations must be testable in plain unit tests");
}
