package pl.krktransit.realtime.ingestion;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("pl.krktransit.realtime.ingestion");

    @Test
    void coreHasRealClassesAndNoFrameworkOrAdapterDependencies() {
        assertFalse(PRODUCTION.stream().filter(type -> type.getPackageName().contains(".domain.")).toList().isEmpty());
        assertFalse(PRODUCTION.stream().filter(type -> type.getPackageName().contains(".application.")).toList().isEmpty());
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "io.lettuce..", "com.google.protobuf..",
                        "com.google.transit..", "java.net.http..", "..adapter..", "..bootstrap..", "..contracts..")
                .check(PRODUCTION);
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAPackage("..application..").check(PRODUCTION);
    }

    @Test
    void adaptersMeetOnlyThroughApplicationPorts() {
        noClasses().that().resideInAPackage("..adapter.outbound..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapter.inbound..", "..bootstrap..")
                .check(PRODUCTION);
        noClasses().that().resideInAPackage("..adapter.inbound..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapter.outbound..", "..bootstrap..")
                .check(PRODUCTION);
        slices().matching("pl.krktransit.realtime.ingestion.adapter.outbound.(*)..")
                .should().notDependOnEachOther().check(PRODUCTION);
    }

    @Test
    void wireFormatsStayInsideTheirAdapters() {
        noClasses().that().resideOutsideOfPackage("..adapter.outbound.redis..")
                .should().dependOnClassesThat().resideInAnyPackage("pl.krktransit.realtime.contracts..", "io.lettuce..")
                .check(PRODUCTION);
        noClasses().that().resideOutsideOfPackage("..adapter.outbound.gtfsrt..")
                .should().dependOnClassesThat().resideInAPackage("com.google.transit..")
                .check(PRODUCTION);
    }
}
