plugins {
    java
    id("com.gradleup.shadow")
}

val mainClassName = "dev.relay.dashboard.DashboardMain"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

/**
 * Deliberately no dependency on :proxy. The two speak over the control channel and
 * nothing else, so a compile-time link would be a second, invisible contract that could
 * drift from the documented one -- and would drag Netty back into this jar.
 */
dependencies {
    implementation(libs.javalin)
    implementation(libs.gson)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.shadowJar {
    archiveClassifier = ""
    archiveBaseName = "relay-dashboard"
    manifest {
        attributes(
            "Main-Class" to mainClassName,
            "Implementation-Title" to "Relay Dashboard",
            "Implementation-Version" to version,
        )
    }
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
