package pl.krktransit.stopevents;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("pl.krktransit.stopevents");

    @Test
    void domainAndApplicationContainRealFrameworkFreeClasses() {
        assertFalse(PRODUCTION.stream().filter(type -> type.getPackageName().contains(".domain.")).toList().isEmpty());
        assertFalse(PRODUCTION.stream().filter(type -> type.getPackageName().contains(".application.")).toList().isEmpty());
        classes().that().resideInAPackage("..domain..").should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "..domain..").check(PRODUCTION);
        classes().that().resideInAPackage("..application..").should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "..domain..", "..application..").check(PRODUCTION);
    }

    @Test
    void adaptersDoNotDependOnEachOtherOrBootstrap() {
        noClasses().that().resideInAPackage("..adapter..").should().dependOnClassesThat()
                .resideInAPackage("..bootstrap..").check(PRODUCTION);
        noClasses().that().resideInAPackage("..adapter.inbound..").should().dependOnClassesThat()
                .resideInAPackage("..adapter.outbound..").check(PRODUCTION);
        noClasses().that().resideInAPackage("..adapter.outbound..").should().dependOnClassesThat()
                .resideInAPackage("..adapter.inbound..").check(PRODUCTION);
        slices().matching("pl.krktransit.stopevents.adapter.outbound.(*)..").should().notDependOnEachOther().check(PRODUCTION);
    }

    @Test
    void generatedContractsStayInsideTheirAdapters() {
        noClasses().that().resideOutsideOfPackages("..adapter.inbound.grpc..", "..contracts..")
                .should().dependOnClassesThat().resideInAPackage("pl.krktransit.stopevents.contracts..").check(PRODUCTION);
        noClasses().that().resideOutsideOfPackage("..adapter.inbound.stream..").should().dependOnClassesThat()
                .resideInAPackage("pl.krktransit.realtime.contracts..").check(PRODUCTION);
        noClasses().that().resideOutsideOfPackage("..adapter.outbound.schedule..").should().dependOnClassesThat()
                .resideInAPackage("pl.krktransit.schedule.contracts..").check(PRODUCTION);
        noClasses().that().resideOutsideOfPackage("..adapter.outbound.redis..").should().dependOnClassesThat()
                .resideInAPackage("..redis.proto..").check(PRODUCTION);
    }
}
