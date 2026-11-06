package com.docqa.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.docqa.user",
        importOptions = ImportOption.DoNotIncludeTests.class)
class UserServiceArchitectureTest {

    @ArchTest
    static final ArchRule controllers_must_not_access_repositories =
            noClasses()
                    .that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAPackage("..repository..")
                    .because("Controller 只能调用 Service，不能直接访问数据层。");

    @ArchTest
    static final ArchRule services_must_not_depend_on_controllers =
            noClasses()
                    .that().resideInAPackage("..service..")
                    .should().dependOnClassesThat().resideInAPackage("..controller..")
                    .because("依赖方向只能是 Controller -> Service。");

    @ArchTest
    static final ArchRule only_agent_gateway_may_use_webclient =
            noClasses()
                    .that().resideOutsideOfPackage("..agentgateway..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.web.reactive.function.client.WebClient")
                    .because("只有 agent-gateway 可以同步调用 Python。");

    @ArchTest
    static final ArchRule only_agent_gateway_may_use_resttemplate =
            noClasses()
                    .that().resideOutsideOfPackage("..agentgateway..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.web.client.RestTemplate")
                    .because("只有 agent-gateway 可以同步调用 Python。");

    @ArchTest
    static final ArchRule only_agent_gateway_may_use_restclient =
            noClasses()
                    .that().resideOutsideOfPackage("..agentgateway..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.web.client.RestClient")
                    .because("只有 agent-gateway 可以同步调用 Python。");
}
