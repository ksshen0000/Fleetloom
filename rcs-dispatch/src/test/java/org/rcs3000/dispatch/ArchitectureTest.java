package org.rcs3000.dispatch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "org.rcs3000.dispatch", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule coreIsFrameworkFree = noClasses()
            .that().resideInAPackage("..dispatch.core..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "com.baomidou..",
                    "org.rcs3000.dispatch.app..", "org.rcs3000.dispatch.api..", "org.rcs3000.dispatch.infra..")
            .because("dispatching rules must be testable without the framework or other services");
}
