plugins {
    java
}

// Paper 1.20.2 runs on Java 17, and a plugin built for 21 fails to load with an
// unhelpful class-version error -- the same reason paper-plugin does this.
tasks.withType<JavaCompile> {
    options.release = 17
}

dependencies {
    compileOnly(libs.paper.api)
    // The packet API. compileOnly because it is inside the Relay plugin jar at runtime,
    // which plugin.yml makes a hard dependency: shading a second copy of PacketFactory
    // into this jar would give the two plugins different classes with the same name and
    // a registry that could never agree with itself.
    compileOnly(project(":paper-plugin"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":paper-plugin"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    val properties = mapOf("version" to project.version)
    inputs.properties(properties)
    filesMatching("plugin.yml") {
        expand(properties)
    }
}

tasks.jar {
    archiveBaseName = "BlockMirror"
}
