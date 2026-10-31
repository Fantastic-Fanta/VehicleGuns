pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
}

plugins {
    // Lets Gradle provision the JDK named in the toolchain blocks if it is missing locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "vehicleguns"

// common/ holds the loader-neutral code. It is not a project of its own: each loader compiles it in with its own
// sources, so a NeoForge class used in common breaks the Fabric build and a Fabric one breaks the NeoForge build.
include("neoforge", "fabric")
