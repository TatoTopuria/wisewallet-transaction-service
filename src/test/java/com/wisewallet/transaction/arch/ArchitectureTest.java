package com.wisewallet.transaction.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
        packages = "com.wisewallet.transaction",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class ArchitectureTest {

    @ArchTest
    static final ArchRule layeringRule = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("com.wisewallet.transaction.domain..")
            .layer("Application").definedBy("com.wisewallet.transaction.application..")
            .layer("Infrastructure").definedBy("com.wisewallet.transaction.infrastructure..")
            .layer("Presentation").definedBy("com.wisewallet.transaction.presentation..")

            // Domain must not depend on any other layer
            .whereLayer("Domain").mayNotAccessAnyLayer()

            // Application may use domain and presentation DTOs/mappers (CQRS use cases bridge both)
            .whereLayer("Application").mayOnlyAccessLayers("Domain", "Presentation")

            // Infrastructure may use domain and application ports
            .whereLayer("Infrastructure").mayOnlyAccessLayers("Domain", "Application")

            // Presentation may use application and domain (DTOs, exceptions, value objects)
            .whereLayer("Presentation").mayOnlyAccessLayers("Application", "Domain");
}
