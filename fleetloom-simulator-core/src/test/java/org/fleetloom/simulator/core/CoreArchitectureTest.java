package org.fleetloom.simulator.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "org.fleetloom.simulator.core", importOptions = ImportOption.DoNotIncludeTests.class)
class CoreArchitectureTest {

    @ArchTest
    static final ArchRule onlyTheProtocol = classes()
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "org.fleetloom.simulator.core..",
                    "org.fleetloom.vda5050..", "org.slf4j..")
            .because("the simulated vehicles must work with any VDA 5050 fleet manager, and in plain unit tests");
}
