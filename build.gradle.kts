import org.gradle.api.JavaVersion
import java.util.Properties

plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "net.teacommontea"

val versionFile = file("version.properties")

fun readBuild(): Triple<Int, Int, Int> {
    val p = Properties()
    versionFile.inputStream().use { p.load(it) }
    return Triple(
        p.getProperty("build.x", "0").toInt(),
        p.getProperty("build.z", "0").toInt(),
        p.getProperty("build.y", "0").toInt(),
    )
}

fun writeBuild(x: Int, z: Int, y: Int) {
    versionFile.writeText(
        "build.x=$x\nbuild.z=$z\nbuild.y=$y\n"
    )
}

fun phaseFor(x: Int, z: Int, y: Int): String {

    val n = x * 100 + z * 10 + y
    return when {
        n >= 100 -> "PUBLIC"
        n >= 10  -> "BETA"
        else     -> "ALPHA"
    }
}

fun displayVersion(): String {
    val (x, z, y) = readBuild()
    return "${phaseFor(x, z, y)}_$x.$z.$y"
}

val incrementBuild by tasks.registering {
    doLast {
        var (x, z, y) = readBuild()
        y += 1
        if (y > 9) { y = 0; z += 1 }
        if (z > 9) { z = 0; x += 1 }
        writeBuild(x, z, y)
        val v = "${phaseFor(x, z, y)}_$x.$z.$y"
        logger.lifecycle("SkStorage build version -> $v")
    }
}

version = displayVersion()

data class Target(
    val paperApi: String,
    val skript: String,
    val javaRelease: Int,
)

val t = Target("1.21.11-R0.1-SNAPSHOT", "2.15.3", 21)

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

val compileLitebansApi by tasks.registering(JavaCompile::class) {
    source = fileTree("litebans-api/src")
    include("**/*.java")
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("litebans-api/classes"))
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

val litebansApiJar by tasks.registering(Jar::class) {
    dependsOn(compileLitebansApi)
    archiveBaseName.set("litebans-api-stub")
    from(compileLitebansApi.flatMap { it.destinationDirectory })
    destinationDirectory.set(layout.buildDirectory.dir("litebans-api"))
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${t.paperApi}")
    compileOnly("com.github.SkriptLang:Skript:${t.skript}")
    compileOnly("org.xerial:sqlite-jdbc:3.46.1.3")
    compileOnly("org.jetbrains:annotations:24.1.0")
    compileOnly("com.google.guava:guava:33.3.1-jre")
    compileOnly("com.intellectualsites.plotsquared:plotsquared-core:7.5.12")
    compileOnly("com.intellectualsites.plotsquared:plotsquared-bukkit:7.5.12") {
        isTransitive = false
    }

    compileOnly(files(litebansApiJar.flatMap { it.archiveFile }))
}

java {
    toolchain {

        languageVersion.set(JavaLanguageVersion.of(t.javaRelease))
    }
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(litebansApiJar)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(t.javaRelease)
}

tasks.processResources {
    dependsOn(incrementBuild)

    inputs.file(versionFile)
    filesMatching("plugin.yml") {
        expand("version" to displayVersion())
    }
}

tasks.shadowJar {
    dependsOn(incrementBuild)
    archiveBaseName.set("SkStorage")
    archiveClassifier.set("")

    archiveVersion.set(provider { displayVersion() })

    doFirst {
        val libs = layout.buildDirectory.dir("libs").get().asFile
        libs.listFiles { f -> f.name.startsWith("SkStorage-") && f.name.endsWith(".jar") }
            ?.forEach { it.delete() }
    }

}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
