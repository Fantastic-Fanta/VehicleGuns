plugins {
    // Declared once here so both loader projects share one plugin classloader.
    kotlin("jvm") version "2.4.0" apply false
    id("net.neoforged.moddev") version "2.0.146" apply false
    id("net.fabricmc.fabric-loom-remap") version "1.15.5" apply false
}
