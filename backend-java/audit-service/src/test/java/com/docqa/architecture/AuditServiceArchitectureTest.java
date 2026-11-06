package com.docqa.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.transaction.annotation.Transactional;

@AnalyzeClasses(
        packages = "com.docqa.audit",
        importOptions = ImportOption.DoNotIncludeTests.class)
class AuditServiceArchitectureTest {

    @ArchTest
    static final ArchRule audit_service_must_not_expose_http_endpoints =
            noClasses()
                    .that().resideInAPackage("com.docqa.audit..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.web.bind.annotation..",
                            "org.springframework.web.servlet..")
                    .because("audit-service has no OpenAPI contract and must stay consumer-only");

    @ArchTest
    static final ArchRule audit_service_must_not_use_webclient =
            noClasses()
                    .that().resideInAPackage("com.docqa.audit..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            "org.springframework.web.reactive.function.client.WebClient")
                    .because("Only agent-gateway may call Python synchronously");

    @ArchTest
    static final ArchRule audit_service_must_not_use_resttemplate =
            noClasses()
                    .that().resideInAPackage("com.docqa.audit..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            "org.springframework.web.client.RestTemplate")
                    .because("Only agent-gateway may call Python synchronously");

    @ArchTest
    static final ArchRule audit_service_must_not_use_restclient =
            noClasses()
                    .that().resideInAPackage("com.docqa.audit..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            "org.springframework.web.client.RestClient")
                    .because("Only agent-gateway may call Python synchronously");

    @ArchTest
    static final ArchRule repositories_must_not_depend_on_services_or_messaging =
            noClasses()
                    .that().resideInAPackage("..repository..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..service..", "..messaging..", "..idempotency..")
                    .because("Repositories are the lowest layer and only expose storage operations");

    @ArchTest
    static final ArchRule messaging_must_not_access_repositories =
            noClasses()
                    .that().resideInAPackage("..messaging..")
                    .should().dependOnClassesThat().resideInAPackage("..repository..")
                    .because("Messaging calls the service, never the repository");

    @ArchTest
    static final ArchRule messaging_must_not_be_transactional =
            noMethods()
                    .that().areDeclaredInClassesThat().resideInAPackage("..messaging..")
                    .should().beAnnotatedWith(Transactional.class)
                    .because("MQ listeners are not transactional boundaries and must not wrap external calls");

    @ArchTest
    static final ArchRule repositories_must_stay_append_only =
            noMethods()
                    .that().areDeclaredInClassesThat().resideInAPackage("..repository..")
                    .should().haveNameMatching("(update|delete|remove|save).*")
                    .because("audit_event is immutable: only INSERT and SELECT are allowed");
}
