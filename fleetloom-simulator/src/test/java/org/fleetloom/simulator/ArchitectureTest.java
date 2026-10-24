package org.fleetloom.simulator;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "org.fleetloom.simulator", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule simulatorWorksWithoutThePlatform = noClasses()
            .that().resideInAnyPackage("org.fleetloom.simulator.app..", "org.fleetloom.simulator.api..")
            .should().dependOnClassesThat().resideInAnyPackage("org.fleetloom.contracts..",
                    "org.fleetloom.simulator.platform..", "org.fleetloom.simulator.scenario..")
            .because("the simulator must also work with other VDA 5050 fleet managers; what ties it to "
                    + "Fleetloom lives in the platform and scenario packages and can be switched off");
}
