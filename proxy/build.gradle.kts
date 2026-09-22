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
    implementation(libs.sqlite)
    implementation(libs.slf4j.api)
    // compileOnly as well as runtimeOnly, for the one class that is a logback
    // extension rather than a user of it: CompanionColour implements logback's
    // converter interface and is loaded by logback itself from logback.xml.
    // Application code still sees only slf4j, which is what runtimeOnly is protecting.
    compileOnly(libs.logback)
    runtimeOnly(libs.logback)

    testImplementation(libs.asm)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // The colour converter is a logback extension, so its test needs logback too.
    testImplementation(libs.logback)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// PromiseRuleTest reads the Paper plugin's compiled classes as well as the proxy's:
// the rule it enforces exists because of a bug that was in the plugin, so covering only
// this module would be covering the wrong one.
tasks.test {
    dependsOn(":paper-plugin:classes")
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
 * Warns when the jar is being replaced under a running proxy.
 *
 * Replacing it does not fail the build and does not stop the proxy either -- the JVM
 * has already loaded what it is using. It fails much later, when that proxy first needs
 * a class it had not loaded yet, as a NoClassDefFoundError deep inside Netty naming
 * something like PoolArena$1. Nothing in that stack trace mentions the jar, so it reads
 * as a Relay bug and gets debugged as one. It has cost this project a session already.
 *
 * A warning rather than a failure: rebuilding while the proxy runs is a perfectly
 * reasonable thing to do when you are about to restart it, which is most of the time.
 */
// PromiseRuleTest reads the Paper plugin's compiled classes as well as the proxy's:
// the rule it enforces exists because of a bug that was in the plugin, so covering only
// this module would be covering the wrong one.
tasks.test {
    dependsOn(":paper-plugin:classes")
}

tasks.shadowJar {
    doFirst {
        // The pid file the proxy writes at startup, rather than a process scan:
        // ProcessHandle.info().commandLine() is empty for other processes on Windows,
        // so a scan finds nothing on the platform this actually runs on.
        val pidFile = rootProject.layout.projectDirectory.file(".relay-run/relay.pid").asFile
        if (!pidFile.exists()) return@doFirst
        val pid = pidFile.readText().trim().toLongOrNull() ?: return@doFirst
        if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) {
            logger.warn(
                "WARNING: replacing the jar while Relay is running as pid $pid. That process " +
                    "keeps working until it needs a class it has not loaded yet, and then fails " +
                    "with a NoClassDefFoundError deep in Netty that looks like a Relay bug. " +
                    "Restart it: py relay.py stop, then py relay.py up."
            )
        }
    }
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
