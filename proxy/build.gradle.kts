plugins {
    java
    id("com.gradleup.shadow")
}

val mainClassName = "dev.relay.RelayBootstrap"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(libs.bundles.netty)
    // Native epoll, for the Linux container in the deployment plan. Absent elsewhere,
    // where Transport falls back to NIO.
    implementation(libs.netty.transport.epoll) { artifact { classifier = "linux-x86_64" } }
    implementation(libs.bundles.adventure)
    implementation(libs.nightconfig.toml)
    implementation(libs.gson)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.shadowJar {
    archiveClassifier = ""
    manifest {
        attributes(
            "Main-Class" to mainClassName,
            "Implementation-Title" to "Relay",
            "Implementation-Version" to version,
        )
    }
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

/**
 * Runs the proxy against a scratch directory rather than the repo root, so the generated
 * relay.toml, logs and secret never land next to the source.
 */
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the proxy with ./run as its working directory"
    mainClass = mainClassName
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = rootProject.layout.projectDirectory.dir("run").asFile
    standardInput = System.`in`
    doFirst { workingDir.mkdirs() }
}
