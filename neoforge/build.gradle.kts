plugins {
    `java-library`
    idea
    kotlin("jvm")
    id("net.neoforged.moddev")
}

fun prop(name: String): String = providers.gradleProperty(name).get()

version = prop("mod_version")
group = prop("mod_group_id")

base {
    archivesName = "${prop("mod_id")}-neoforge"
}

// The shared code in common/ is compiled in as part of this project.
val common: File = rootProject.file("common/src/main")

java.toolchain.languageVersion = JavaLanguageVersion.of(21)
kotlin {
    jvmToolchain(21)
}

repositories {
    maven("https://thedarkcolour.github.io/KotlinForForge/") { name = "Kotlin for Forge" }
}

neoForge {
    version = prop("neo_version")

    parchment {
        mappingsVersion = prop("parchment_mappings_version")
        minecraftVersion = prop("parchment_minecraft_version")
    }

    runs {
        create("client") {
            client()
            gameDirectory = rootProject.file("run")
        }
        create("server") {
            server()
            gameDirectory = rootProject.file("run")
            programArgument("--nogui")
        }
    }

    mods {
        create(prop("mod_id")) {
            sourceSet(sourceSets.main.get())
        }
    }
}

sourceSets.main {
    kotlin.srcDir(common.resolve("kotlin"))
    java.srcDir(common.resolve("java"))
    resources.srcDir(common.resolve("resources"))
}

dependencies {
    implementation("thedarkcolour:kotlinforforge-neoforge:${prop("kff_version")}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Runs JUnit tests inside an FML-bootstrapped environment so Minecraft classes are usable.
neoForge.unitTest {
    enable()
    testedMod = neoForge.mods.getByName(prop("mod_id"))
}

// Expands ${...} placeholders in src/main/templates into the real mod metadata.
val generateModMetadata by tasks.registering(ProcessResources::class) {
    val replaceProperties = mapOf(
        "minecraft_version_range" to prop("minecraft_version_range"),
        "neo_version" to prop("neo_version"),
        "loader_version_range" to prop("loader_version_range"),
        "kff_version_range" to prop("kff_version_range"),
        "mod_id" to prop("mod_id"),
        "mod_name" to prop("mod_name"),
        "mod_license" to prop("mod_license"),
        "mod_version" to prop("mod_version"),
        "mod_authors" to prop("mod_authors"),
        "mod_description" to prop("mod_description"),
    )
    inputs.properties(replaceProperties)
    expand(replaceProperties)
    from("src/main/templates")
    into("build/generated/sources/modMetadata")
}
sourceSets.main { resources.srcDir(generateModMetadata) }
neoForge.ideSyncTask(generateModMetadata)

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}
