rootProject.name = "relay"

include("proxy", "paper-plugin", "dashboard")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Paper's API, for the debug plugin that runs on the backend side.
        maven("https://repo.papermc.io/repository/maven-public/") {
            content { includeGroupByRegex("io\\.papermc.*|com\\.destroystokyo.*|net\\.md-5") }
        }
    }
    versionCatalogs {
        create("libs") {
            version("netty", "4.1.115.Final")
            version("adventure", "4.17.0")
            version("slf4j", "2.0.16")
            version("paper", "1.20.2-R0.1-SNAPSHOT")

            library("netty-handler", "io.netty", "netty-handler").versionRef("netty")
            library("netty-codec", "io.netty", "netty-codec").versionRef("netty")
            library("netty-transport", "io.netty", "netty-transport").versionRef("netty")
            library("netty-transport-epoll", "io.netty", "netty-transport-native-epoll").versionRef("netty")
            library("netty-codec-haproxy", "io.netty", "netty-codec-haproxy").versionRef("netty")

            library("adventure-api", "net.kyori", "adventure-api").versionRef("adventure")
            library("adventure-gson", "net.kyori", "adventure-text-serializer-gson").versionRef("adventure")
            library("adventure-legacy", "net.kyori", "adventure-text-serializer-legacy").versionRef("adventure")
            library("adventure-minimessage", "net.kyori", "adventure-text-minimessage").versionRef("adventure")

            library("nightconfig-toml", "com.electronwill.night-config", "toml").version("3.8.1")
            library("gson", "com.google.code.gson", "gson").version("2.11.0")

            // Javalin brings Jetty with it. Gson is already here for components, so the
            // dashboard serialises with that rather than adding Jackson for one job.
            library("javalin", "io.javalin", "javalin").version("6.3.0")

            library("slf4j-api", "org.slf4j", "slf4j-api").versionRef("slf4j")
            library("logback", "ch.qos.logback", "logback-classic").version("1.5.12")

            library("paper-api", "io.papermc.paper", "paper-api").versionRef("paper")

            library("junit-bom", "org.junit", "junit-bom").version("5.11.3")
            library("junit-jupiter", "org.junit.jupiter", "junit-jupiter").withoutVersion()
            library("junit-platform-launcher", "org.junit.platform", "junit-platform-launcher").withoutVersion()

            bundle("netty", listOf("netty-handler", "netty-codec", "netty-transport", "netty-codec-haproxy"))
            bundle("adventure", listOf("adventure-api", "adventure-gson", "adventure-legacy", "adventure-minimessage"))
        }
    }
}
