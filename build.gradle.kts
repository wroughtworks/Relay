// The root project holds no sources; it only carries shared configuration and the
// plugin versions the modules resolve against.
plugins {
    id("com.gradleup.shadow") version "9.0.2" apply false
}

// Settings shared by both modules. Java versions deliberately differ and are set
// per module: the proxy targets 21, while the Paper plugin has to run inside a
// server that may still be on 17.
subprojects {
    apply(plugin = "java")

    group = "dev.relay"
    version = "0.1.0-SNAPSHOT"

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:all,-serial,-processing")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
