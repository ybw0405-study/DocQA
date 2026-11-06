package com.docqa.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.transaction.annotation.Transactional;

@AnalyzeClasses(
        packages = "com.docqa.reminder",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ReminderServiceArchitectureTest {

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
    static final ArchRule only_agent_gateway_may_use_webclient =
            noClasses()
                    .that().resideOutsideOfPackage("..agentgateway..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.web.reactive.function.client.WebClient")
                    .because("Only agent-gateway may call Python synchronously");

    @ArchTest
    static final ArchRule only_agent_gateway_may_use_resttemplate =
            noClasses()
                    .that().resideOutsideOfPackage("..agentgateway..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.web.client.RestTemplate")
                    .because("Only agent-gateway may call Python synchronously");

    @ArchTest
    static final ArchRule only_agent_gateway_may_use_restclient =
            noClasses()
                    .that().resideOutsideOfPackage("..agentgateway..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.web.client.RestClient")
                    .because("Only agent-gateway may call Python synchronously");
}
