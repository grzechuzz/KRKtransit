package pl.krktransit.livevehicles;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private static final JavaClasses PRODUCTION = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("pl.krktransit.livevehicles");

    @Test
    void domainAndApplicationAreRealAndFrameworkFree() {
        assertThat(PRODUCTION.stream().filter(type -> type.getPackageName().contains(".domain")).toList()).isNotEmpty();
        assertThat(PRODUCTION.stream().filter(type -> type.getPackageName().contains(".application")).toList()).isNotEmpty();
        classes().that().resideInAPackage("..domain..").should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "..domain..").check(PRODUCTION);
        classes().that().resideInAPackage("..application..").should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "..domain..", "..application..").check(PRODUCTION);
    }

    @Test
    void adaptersMeetOnlyThroughApplicationPortsAndDependOnNoOtherService() {
        noClasses().that().resideInAPackage("..adapter..").should().dependOnClassesThat().resideInAPackage("..bootstrap..").check(PRODUCTION);
        slices().matching("pl.krktransit.livevehicles.adapter.(*).(*)..").should().notDependOnEachOther().check(PRODUCTION);
        noClasses().should().dependOnClassesThat().resideInAnyPackage("pl.krktransit.stopevents..",
                "pl.krktransit.realtime.ingestion..", "pl.krktransit.schedule..").check(PRODUCTION);
        noClasses().that().resideOutsideOfPackage("..adapter.inbound.stream..").should().dependOnClassesThat()
                .resideInAPackage("pl.krktransit.realtime.contracts..").check(PRODUCTION);
    }
}
