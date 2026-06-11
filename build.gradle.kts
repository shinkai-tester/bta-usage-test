@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import java.io.File

val kotlinVersion = "2.4.0" // 2.4.255-SNAPSHOT + mavenLocal() for faster testing of fixes

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.power-assert") version "2.4.0"
}

configurations {
    create("myCompiler")
    create("oldCompilerForCancellation") // For backward compatibility testing
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api:$kotlinVersion")
    runtimeOnly("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    // Keep compiler impl off the app classpath; they will be passed explicitly at runtime
    "myCompiler"("org.jetbrains.kotlin:kotlin-build-tools-impl:$kotlinVersion")
    // Old compiler for backward compatibility testing (cancellation not supported before 2.3.20)
    "oldCompilerForCancellation"("org.jetbrains.kotlin:kotlin-build-tools-impl:2.3.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:6.1.0")
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
        "old.compiler.cancellation.impl.classpath",
        configurations.getByName("oldCompilerForCancellation").files.joinToString(File.pathSeparator) { it.absolutePath }
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