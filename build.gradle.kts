@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import java.io.File

plugins {
    kotlin("jvm") version "2.4.0-dev-539"
    kotlin("plugin.power-assert") version "2.3.20-Beta1"
}

configurations {
    create("myCompiler")
    create("oldCompiler") // For backward compatibility testing (pre-2.3.20)
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0-dev-539")
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api:2.4.0-dev-539")
    runtimeOnly("org.jetbrains.kotlin:kotlin-reflect:2.4.0-dev-539")
    // Keep compiler impl off the app classpath; they will be passed explicitly at runtime
    "myCompiler"("org.jetbrains.kotlin:kotlin-build-tools-impl:2.4.0-dev-539")
    // Old compiler for backward compatibility testing (cancellation not supported before 2.3.20)
    "oldCompiler"("org.jetbrains.kotlin:kotlin-build-tools-impl:2.3.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.2")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("junit.jupiter.testclass.order.default", $$"org.junit.jupiter.api.ClassOrderer$OrderAnnotation")
    // Provide an isolated impl classpath to tests for deterministic behavior
    systemProperty(
        "compiler.impl.classpath",
        configurations.getByName("myCompiler").files.joinToString(File.pathSeparator) { it.absolutePath }
    )
    // Provide old compiler classpath for backward compatibility testing
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
    functions = listOf("kotlin.assert", "kotlin.test.assertTrue", "kotlin.test.assertEquals", "kotlin.test.assertNull")
}