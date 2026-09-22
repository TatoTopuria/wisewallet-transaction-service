plugins {
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDepMgmt)
    java
    jacoco
    id("com.github.ben-manes.versions") version "0.51.0"
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot.bom.get().toString())
        mavenBom(libs.spring.cloud.bom.get().toString())
        mavenBom(libs.testcontainers.bom.get().toString())
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    // Lombok — must come first
    compileOnly(libs.lombok.lib)
    annotationProcessor(libs.lombok.lib)
    annotationProcessor(libs.lombok.mapstruct.binding)

    // MapStruct
    implementation(libs.mapstruct.lib)
    annotationProcessor(libs.mapstruct.processor)

    // Spring Boot
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.cloud.starter.openfeign)

    // Resilience4j
    implementation(libs.resilience4j.spring.boot3)
    implementation(libs.resilience4j.feign)

    // JJWT
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    // Database
    runtimeOnly(libs.postgresql)
    implementation(libs.liquibase.core)

    // Observability
    runtimeOnly(libs.micrometer.prometheus)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.springdoc.openapi.webmvc)

    // Test
    testCompileOnly(libs.lombok.lib)
    testAnnotationProcessor(libs.lombok.lib)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.wiremock)
    testImplementation(libs.archunit.junit5)
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.85".toBigDecimal()
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.75".toBigDecimal()
            }
        }
    }
}
