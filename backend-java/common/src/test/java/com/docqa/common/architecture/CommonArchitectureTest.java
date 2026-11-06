package com.docqa.common.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.docqa.common",
        importOptions = ImportOption.DoNotIncludeTests.class)
class CommonArchitectureTest {

    @ArchTest
    static final ArchRule common_must_not_depend_on_infrastructure = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.data.jpa..",
                    "org.springframework.data.redis..",
                    "org.apache.rocketmq..",
                    "io.minio..",
                    "org.springframework.web.reactive.function.client..",
                    "org.springframework.web.client..",
                    "java.net.http..",
                    "java.sql..",
                    "javax.sql..");

    @ArchTest
    static final ArchRule common_must_not_define_business_layers = noClasses()
            .should()
            .resideInAnyPackage(
                    "..controller..",
                    "..service..",
                    "..manager..",
                    "..repository..");
}
