package dq.engine;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Guards the package structure of the library's own (non-test) classes. */
class ArchitectureTest {

    private static final JavaClasses ENGINE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dq.engine");

    /** {@code catalog} and {@code source} are leaves; only {@code run} depends on them. */
    @Test
    void featurePackagesAreFreeOfCycles() {
        slices().matching("dq.engine.(*)..").should().beFreeOfCycles().check(ENGINE);
    }
}
