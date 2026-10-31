plugins {
    `java-library`
    kotlin("jvm")
    id("net.fabricmc.fabric-loom-remap")
}

fun prop(name: String): String = providers.gradleProperty(name).get()

version = prop("mod_version")
group = prop("mod_group_id")

base {
    archivesName = "${prop("mod_id")}-fabric"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)
kotlin {
    jvmToolchain(21)
}

// The shared code in common/ is compiled in as part of this project.
val common: File = rootProject.file("common/src/main")

sourceSets.main {
    kotlin.srcDir(common.resolve("kotlin"))
    java.srcDir(common.resolve("java"))
    resources.srcDir(common.resolve("resources"))
}

repositories {
    maven("https://maven.parchmentmc.org") { name = "Parchment" }
}

loom {
    mods {
        create(prop("mod_id")) { sourceSet(sourceSets.main.get()) }
    }
    runs {
        // Named so a bare `./gradlew runClient` / `runServer` from the root means the NeoForge runs.
        remove(getByName("client"))
        remove(getByName("server"))
        create("fabricClient") {
            client()
            configName = "Fabric Client"
            runDir("../run-fabric")
        }
        create("fabricServer") {
            server()
            configName = "Fabric Server"
            runDir("../run-fabric")
            programArgs("--nogui")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${prop("minecraft_version")}")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-${prop("parchment_minecraft_version")}:${prop("parchment_mappings_version")}@zip")
    })
    modImplementation("net.fabricmc:fabric-loader:${prop("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${prop("flk_version")}")
}

tasks.processResources {
    val replaceProperties = mapOf(
        "minecraft_version" to prop("minecraft_version"),
        "fabric_loader_version_range" to prop("fabric_loader_version_range"),
        "mod_id" to prop("mod_id"),
        "mod_name" to prop("mod_name"),
        "mod_license" to prop("mod_license"),
        "mod_version" to prop("mod_version"),
        "mod_authors" to prop("mod_authors"),
        "mod_description" to prop("mod_description"),
    )
    inputs.properties(replaceProperties)
    filesMatching("fabric.mod.json") { expand(replaceProperties) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
