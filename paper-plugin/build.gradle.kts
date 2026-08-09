plugins {
    java
}

// Paper 1.20.2 runs on Java 17, and a plugin built for 21 fails to load with an
// unhelpful class-version error. `--release 17` produces 17-compatible bytecode
// (and enforces the 17 API) using whichever JDK Gradle is already running on, so
// building this does not require a second JDK to be installed.
tasks.withType<JavaCompile> {
    options.release = 17
}

dependencies {
    compileOnly(libs.paper.api)
    // Netty ships with the server; needed at compile time for the pipeline handler.
    compileOnly(libs.netty.transport)
    compileOnly(libs.netty.handler)
}

tasks.processResources {
    // Keep the version in plugin.yml in step with the build rather than by hand.
    val properties = mapOf("version" to project.version)
    inputs.properties(properties)
    filesMatching("plugin.yml") {
        expand(properties)
    }
}

tasks.jar {
    archiveBaseName = "RelayDebug"
}
