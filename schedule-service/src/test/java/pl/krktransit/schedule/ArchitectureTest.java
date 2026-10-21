package pl.krktransit.schedule;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ArchitectureTest {
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("pl.krktransit.schedule");

    @Test
    void coreHasRealClassesAndNoFrameworkOrAdapterDependencies() {
        assertFalse(PRODUCTION.stream().filter(type -> type.getPackageName().contains(".domain.")).toList().isEmpty());
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "io.grpc..", "com.google.protobuf..",
                        "java.sql..", "javax.sql..", "org.postgresql..", "org.apache.commons.csv..",
                        "..adapter..", "..bootstrap..", "..contracts..")
                .check(PRODUCTION);
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAPackage("..application..").check(PRODUCTION);
    }

    @Test
    void outboundAdaptersOnlyMeetThroughCorePorts() {
        for (String adapter : new String[]{"postgres", "http", "archive", "gtfscsv"}) {
            noClasses().that().resideInAPackage("..adapter.outbound." + adapter + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..adapter.inbound..", "..bootstrap..", "..contracts..")
                    .check(PRODUCTION);
        }
        slices().matching("pl.krktransit.schedule.adapter.outbound.(*)..")
                .should().notDependOnEachOther().check(PRODUCTION);
    }
}
