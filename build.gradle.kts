import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.5.12"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "watch.nabu"
version = "0.0.1-SNAPSHOT"

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

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Database
    implementation("org.postgresql:postgresql")
    implementation("org.liquibase:liquibase-core")

    // Redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // WebSocket / STOMP — real-time notifications
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // Security
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    // JWT — jjwt 0.12.x (api + runtime impl + Jackson serialiser)
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // TOTP — step-up authentication for Admin force-scan bypass
    // Zero-dependency, actively maintained; eliminates transitive CVEs from dev.samstevens.totp
    implementation("com.j256.two-factor-auth:two-factor-auth:1.3")
    // QR code image generation (server-side PNG for MFA setup screen)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    // Object Storage (S3-compatible: MinIO, AWS S3, etc.)
    implementation("io.minio:minio:8.6.0")

    // PDF generation (OpenPDF: LGPL-2.1 OR MPL-2.0, iText 4 fork, no commercial license required)
    implementation("com.github.librepdf:openpdf:2.0.3")

    // Email
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Testcontainers
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    // Testcontainers cannot run tests in parallel — each test class would try to
    // start its own containers with the same names/ports, causing conflicts.
    maxParallelForks = 1
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "**/domain/model/**",
                    "**/infrastructure/config/**",
                    "**/NabuWatchApplication*"
                )
            }
        })
    )
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    doFirst {
        val envFile = rootProject.file(".env.local")
        if (envFile.exists()) {
            envFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .forEach { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) {
                        val key = line.substring(0, idx).trim()
                        val value = line.substring(idx + 1).trim()
                        environment(key, value)
                    }
                }
            println("[bootRun] .env.local loaded (${envFile.absolutePath})")
        } else {
            println("[bootRun] WARNING: .env.local not found at ${envFile.absolutePath}")
        }
    }
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("nabu-watch.jar")
}
