package org.fleetloom.dispatch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "org.fleetloom.dispatch", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule coreIsFrameworkFree = noClasses()
            .that().resideInAPackage("..dispatch.core..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "com.baomidou..",
                    "org.fleetloom.dispatch.app..", "org.fleetloom.dispatch.api..", "org.fleetloom.dispatch.infra..")
            .because("dispatching rules must be testable without the framework or other services");
}
