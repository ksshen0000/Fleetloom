package org.rcs3000.fleet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "org.rcs3000.fleet", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule coreIsFrameworkFree = noClasses()
            .that().resideInAPackage("..fleet.core..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                    "org.rcs3000.fleet.app..", "org.rcs3000.fleet.api..", "org.rcs3000.fleet.infra..");

    @ArchTest
    static final ArchRule appDoesNotDependOnAdapters = noClasses()
            .that().resideInAPackage("..fleet.app..")
            .should().dependOnClassesThat().resideInAnyPackage("org.rcs3000.fleet.api..", "org.rcs3000.fleet.infra..",
                    "org.springframework.data..", "org.springframework.web..");
}
