@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import java.io.File

val kotlinVersion = "2.4.0-dev-2633" // 2.4.255-SNAPSHOT + mavenLocal() for faster testing of fixes

plugins {
    kotlin("jvm") version "2.4.0-dev-2633"
    kotlin("plugin.power-assert") version "2.3.20-Beta1"
}

configurations {
    create("myCompiler")
    create("oldCompiler") // For backward compatibility testing (pre-2.3.20)
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api:$kotlinVersion")
    runtimeOnly("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    // Keep compiler impl off the app classpath; they will be passed explicitly at runtime
    "myCompiler"("org.jetbrains.kotlin:kotlin-build-tools-impl:$kotlinVersion")
    // Old compiler for backward compatibility testing (cancellation not supported before 2.3.20)
    "oldCompiler"("org.jetbrains.kotlin:kotlin-build-tools-impl:2.3.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:6.0.2")
}

tasks.test {
    useJUnitPlatform()
    // Provide an isolated impl classpath to tests for deterministic behavior
    systemProperty(
        "compiler.impl.classpath",
        configurations.getByName("myCompiler").files.joinToString(File.pathSeparator) { it.absolutePath }
    )
    // Provide an old compiler classpath for backward compatibility testing
    systemProperty(
        "old.compiler.impl.classpath",
        configurations.getByName("oldCompiler").files.joinToString(File.pathSeparator) { it.absolutePath }
    )
}

// Run the sample MainKt and pass the compiler implementation classpath as args
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs MainKt with isolated compiler implementation classpath"
    mainClass.set("MainKt")
    classpath = sourceSets.main.get().runtimeClasspath
    args = configurations.getByName("myCompiler").files.map { it.absolutePath }
}

powerAssert {
    functions.set(
        listOf(
            "kotlin.assert",
            "kotlin.test.assertTrue",
            "kotlin.test.assertFalse",
            "kotlin.test.assertEquals",
            "kotlin.test.assertNull"
        )
    )
}