package pl.krktransit.publicapi;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
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
            .importPackages("pl.krktransit.publicapi");

    @Test
    void applicationIsRealAndFrameworkFree() {
        assertThat(PRODUCTION.stream().filter(type -> type.getPackageName().contains(".application")).toList()).isNotEmpty();
        classes().that().resideInAPackage("..application..").should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "..application..").check(PRODUCTION);
    }

    @Test
    void adaptersMeetOnlyThroughApplicationPorts() {
        noClasses().that().resideInAPackage("..adapter..").should().dependOnClassesThat().resideInAPackage("..bootstrap..")
                .check(PRODUCTION);
        slices().matching("pl.krktransit.publicapi.adapter.(*).(*)..").should().notDependOnEachOther().check(PRODUCTION);
        noClasses().that().resideInAPackage("..adapter.outbound..").should().dependOnClassesThat()
                .resideInAPackage("org.springframework..").check(PRODUCTION);
    }

    @Test
    void onlyTheGrpcAdapterSeesProtobufContractsAndNoServiceInternals() {
        noClasses().that().resideOutsideOfPackage("..adapter.outbound.grpc..").should().dependOnClassesThat()
                .resideInAnyPackage("..contracts.v1..", "com.google.protobuf..").check(PRODUCTION);
        noClasses().should().dependOnClassesThat(resideInAnyPackage("pl.krktransit.schedule..", "pl.krktransit.stopevents..",
                "pl.krktransit.livevehicles..", "pl.krktransit.realtime..", "pl.krktransit.prediction..").and(resideOutsideOfPackage("..contracts.v1..")))
                .check(PRODUCTION);
    }
}
