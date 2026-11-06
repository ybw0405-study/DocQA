package com.docqa.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.transaction.annotation.Transactional;

@AnalyzeClasses(
        packages = "com.docqa.agentgateway",
        importOptions = ImportOption.DoNotIncludeTests.class)
class AgentGatewayArchitectureTest {

    @ArchTest
    static final ArchRule controllers_must_not_access_repositories =
            noClasses()
                    .that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAPackage("..repository..")
                    .because("Controller can only call services, never repositories");

    @ArchTest
    static final ArchRule services_must_not_depend_on_controllers =
            noClasses()
                    .that().resideInAPackage("..service..")
                    .should().dependOnClassesThat().resideInAPackage("..controller..")
                    .because("The dependency direction is Controller -> Service");

    @ArchTest
    static final ArchRule controllers_must_not_be_transactional =
            noMethods()
                    .that().areDeclaredInClassesThat().resideInAPackage("..controller..")
                    .should().beAnnotatedWith(Transactional.class)
                    .because("Transactions belong to services, not controllers");

    @ArchTest
    static final ArchRule web_layer_must_not_call_python_directly =
            noClasses()
                    .that().resideInAPackage("..web..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("com.docqa.agentgateway.service.PythonAgentClient")
                    .because("Only services may invoke Python; filters handle authentication only");

    @ArchTest
    static final ArchRule messaging_must_not_depend_on_controllers =
            noClasses()
                    .that().resideInAPackage("..messaging..")
                    .should().dependOnClassesThat().resideInAPackage("..controller..")
                    .because("Outbox relay is a background concern and must not call controllers");

    @ArchTest
    static final ArchRule services_must_not_depend_on_messaging =
            noClasses()
                    .that().resideInAPackage("..service..")
                    .should().dependOnClassesThat().resideInAPackage("..messaging..")
                    .because("Relay wiring stays in messaging; services only write the outbox");
}
