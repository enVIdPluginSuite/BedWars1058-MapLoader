import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "8.3.10"
}

group = "dev.etran"
version = "2.0.1"

val bw1058Version: String by project

repositories {
    mavenCentral()
    // Spigot API 1.8.8 - we compile against the OLDEST supported API so we cannot
    // accidentally use anything that is missing on 1.8.
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    // Paper/Spigot API 1.16.5+ - only used by the tiny "modern" source set (ChunkData generator).
    maven("https://repo.papermc.io/repository/maven-public/")
    // BedWars1058 API.
    maven("https://repo.andrei1058.com/releases/")
    maven("https://repo.andrei1058.com/snapshots/")
}

// ---------------------------------------------------------------------------------------------
// Source sets
// ---------------------------------------------------------------------------------------------
// `main`   -> everything, compiled against Spigot 1.8.8 (binary compatible with 1.8 -> 26.x).
// `modern` -> a single class that overrides ChunkGenerator#generateChunkData (1.13+ API only).
//             It is loaded by name at runtime, so `main` never links against it.
val modern: SourceSet by sourceSets.creating

dependencies {
    // --- Bukkit API (oldest supported) ---
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") { isTransitive = false }
    compileOnly("com.google.guava:guava:21.0")
    compileOnly("org.yaml:snakeyaml:1.30")
    compileOnly("com.google.code.gson:gson:2.8.9")
    compileOnly("org.jetbrains:annotations:24.0.1")

    // --- BedWars1058 API ---
    // Resolution order:
    //   1. -PbedwarsApiJar=/path/to/bedwars-api.jar   (explicit)
    //   2. ./libs/*.jar                               (drop BedWars1058.jar or bedwars-api.jar there)
    //   3. com.andrei1058.bedwars:bedwars-api:<bw1058Version> from repo.andrei1058.com
    val explicitJar = providers.gradleProperty("bedwarsApiJar").orNull
    val localLibs = fileTree("libs") { include("*.jar") }
    when {
        explicitJar != null -> compileOnly(files(explicitJar))
        !localLibs.isEmpty -> compileOnly(localLibs)
        else -> compileOnly("com.andrei1058.bedwars:bedwars-api:$bw1058Version") { isTransitive = false }
    }

    // --- Modern generator (compiled against a newer API that has ChunkData) ---
    "modernCompileOnly"("org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT") { isTransitive = false }
    "modernCompileOnly"("org.jetbrains:annotations:24.0.1")

    // --- Tests (pure logic only, no server needed) ---
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------------------------------------------------------------------------------------------
// Compiler settings
// ---------------------------------------------------------------------------------------------
// BedWars1058 itself targets Java 11, so Java 11 bytecode runs everywhere BedWars1058 does.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        // Fail the build if we accidentally call a JDK API newer than Java 11.
        freeCompilerArgs.addAll("-Xjdk-release=11", "-Xjvm-default=all")
    }
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------------------------
// Packaging
// ---------------------------------------------------------------------------------------------
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("BedWars1058-MapLoader-${project.version}.jar")

    // Our own classes + the tiny modern generator.
    from(modern.output)

    // Kotlin's stdlib is not provided by Bukkit servers (and Spigot's library loader does not exist
    // before 1.16.5), so we shade and relocate it to avoid clashing with other Kotlin plugins.
    relocate("kotlin", "dev.bwworldloader.libs.kotlin")
    relocate("org.jetbrains.annotations", "dev.bwworldloader.libs.jb.annotations")
    relocate("org.intellij.lang.annotations", "dev.bwworldloader.libs.intellij.annotations")

    // NOTE: do NOT enable minimize(): the generator classes are loaded by name via reflection.
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**", "META-INF/versions/**")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
